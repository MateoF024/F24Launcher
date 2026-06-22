package f24launcher.auth;

import f24launcher.auth.MicrosoftAuth.AuthException;
import f24launcher.auth.MicrosoftAuth.DeviceCode;
import f24launcher.auth.MicrosoftAuth.MinecraftSession;
import f24launcher.auth.MicrosoftAuth.MsTokens;
import f24launcher.util.AppExecutors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;

/**
 * Lógica de cuentas: gestiona el almacén persistente y orquesta el login con
 * Microsoft (device code), el refresco de tokens y la resolución de la cuenta
 * activa a una {@link Account} lista para lanzar el juego.
 */
public class AccountService {

    private static final Logger log = LoggerFactory.getLogger(AccountService.class);

    private final AccountStore store = new AccountStore();
    private final MicrosoftAuth ms = new MicrosoftAuth();
    private final SkinHistory history = new SkinHistory();

    /** Notifica el resultado del login con Microsoft (para emitirlo por WS). */
    public interface LoginListener {
        void onSuccess(StoredAccount account);
        void onError(String message);
    }

    // ── Almacén ──
    public List<StoredAccount> list() { return store.list(); }
    public StoredAccount get(String id) { return store.get(id); }
    public StoredAccount getActive() { return store.getActive(); }
    public void setActive(String id) { store.setActive(id); }
    public boolean remove(String id) { return store.remove(id); }

    public StoredAccount addOffline(String username) {
        String name = (username == null || username.isBlank()) ? "Player" : username.trim();
        UUID uuid = UUID.nameUUIDFromBytes(("OfflinePlayer:" + name).getBytes(StandardCharsets.UTF_8));
        StoredAccount a = new StoredAccount();
        a.id = "offline:" + name;
        a.type = "offline";
        a.username = name;
        a.uuid = uuid.toString().replace("-", "");
        return store.upsert(a);
    }

    public boolean microsoftConfigured() { return MicrosoftAuth.clientIdConfigured(); }

    /** Datos que el frontend muestra para el device code. */
    public record LoginPrompt(String userCode, String verificationUri, int expiresIn) {}

    // ── Login Microsoft (device code) ──
    /**
     * Inicia el login: pide un device code y arranca el sondeo en segundo plano.
     * Devuelve el código y la URL que el usuario debe abrir; cuando confirma en
     * el navegador, completa la cuenta y avisa por el {@code listener} (WS).
     */
    public LoginPrompt beginMicrosoftLogin(LoginListener listener) throws AuthException {
        if (!MicrosoftAuth.clientIdConfigured()) {
            throw new AuthException("Falta el client_id de Microsoft (no configurado).");
        }
        DeviceCode dc = ms.startDeviceCode();
        AppExecutors.io().submit(() -> {
            try {
                MsTokens tokens = ms.pollForTokens(dc);
                StoredAccount a = completeFromTokens(tokens);
                listener.onSuccess(a);
            } catch (AuthException e) {
                listener.onError(e.getMessage());
            }
        });
        return new LoginPrompt(dc.userCode(), dc.verificationUri(), dc.expiresIn());
    }

    private StoredAccount completeFromTokens(MsTokens tokens) throws AuthException {
        MinecraftSession s = ms.completeMinecraftAuth(tokens.accessToken());
        StoredAccount a = new StoredAccount();
        a.id = s.uuid();
        a.type = "microsoft";
        a.username = s.name();
        a.uuid = s.uuid();
        a.xuid = s.xuid();
        a.skinUrl = s.skinUrl();
        a.mcAccessToken = s.accessToken();
        a.refreshToken = tokens.refreshToken();
        a.expiresAt = System.currentTimeMillis() + s.expiresIn() * 1000L;
        a.active = true;
        return store.upsert(a);
    }

    /** Resuelve la cuenta activa (o el nombre offline dado) a una cuenta de lanzamiento. */
    public Account resolveLaunchAccount(String offlineFallback) throws AuthException {
        StoredAccount active = store.getActive();
        if (active == null) return OfflineAuth.create(offlineFallback);
        if (!active.isMicrosoft()) return OfflineAuth.create(active.username);
        return toMicrosoftLaunchAccount(active);
    }

    private Account toMicrosoftLaunchAccount(StoredAccount a) throws AuthException {
        String token = freshToken(a);
        return new Account(a.username, a.uuid, token, "msa", a.xuid);
    }

    /** Devuelve un mcAccessToken válido, refrescando la sesión si caducó. */
    private String freshToken(StoredAccount a) throws AuthException {
        if (a == null || !a.isMicrosoft()) throw new AuthException("La cuenta no es de Microsoft.");
        if (a.tokenExpired()) {
            if (a.refreshToken == null) throw new AuthException("La sesión caducó. Vuelve a iniciar sesión.");
            MsTokens t = ms.refresh(a.refreshToken);
            MinecraftSession s = ms.completeMinecraftAuth(t.accessToken());
            a.mcAccessToken = s.accessToken();
            a.refreshToken = t.refreshToken();
            a.xuid = s.xuid();
            a.skinUrl = s.skinUrl();
            a.username = s.name();
            a.expiresAt = System.currentTimeMillis() + s.expiresIn() * 1000L;
            store.upsert(a);
        }
        return a.mcAccessToken;
    }

    // ── Skins y capas (cuenta Microsoft) ──

    public MicrosoftAuth.Profile profile(String id) throws AuthException {
        StoredAccount a = require(id);
        return syncSkin(a, ms.fetchProfile(freshToken(a)));
    }

    public MicrosoftAuth.Profile changeSkinUrl(String id, String url, String variant) throws AuthException {
        StoredAccount a = require(id);
        return recordActive(a, syncSkin(a, ms.changeSkinUrl(freshToken(a), url, variant)));
    }

    public MicrosoftAuth.Profile uploadSkin(String id, byte[] png, String fileName, String variant)
            throws AuthException {
        StoredAccount a = require(id);
        return recordActive(a, syncSkin(a, ms.uploadSkin(freshToken(a), png, fileName, variant)));
    }

    public List<SkinHistory.Entry> skins(String id) throws AuthException {
        return history.list(require(id).uuid);
    }

    private MicrosoftAuth.Profile recordActive(StoredAccount a, MicrosoftAuth.Profile p) {
        for (MicrosoftAuth.ProfileSkin s : p.skins()) {
            if ("ACTIVE".equalsIgnoreCase(s.state())) {
                history.record(a.uuid, s.url(), s.variant());
                break;
            }
        }
        return p;
    }

    public MicrosoftAuth.Profile resetSkin(String id) throws AuthException {
        StoredAccount a = require(id);
        return syncSkin(a, ms.resetSkin(freshToken(a)));
    }

    public MicrosoftAuth.Profile cape(String id, String capeId) throws AuthException {
        StoredAccount a = require(id);
        String token = freshToken(a);
        return syncSkin(a, (capeId == null || capeId.isBlank())
                ? ms.hideCape(token) : ms.showCape(token, capeId));
    }

    private StoredAccount require(String id) throws AuthException {
        StoredAccount a = store.get(id);
        if (a == null) throw new AuthException("Cuenta no encontrada.");
        return a;
    }

    /** Persiste en la cuenta la URL de la skin activa del perfil recién leído. */
    private MicrosoftAuth.Profile syncSkin(StoredAccount a, MicrosoftAuth.Profile p) {
        String active = null;
        for (MicrosoftAuth.ProfileSkin s : p.skins()) {
            if ("ACTIVE".equalsIgnoreCase(s.state())) { active = s.url(); break; }
        }
        if (active != null && !active.equals(a.skinUrl)) {
            a.skinUrl = active;
            store.upsert(a);
        }
        return p;
    }
}
