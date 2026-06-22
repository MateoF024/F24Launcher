package f24launcher.auth;

import f24launcher.core.LauncherPaths;
import f24launcher.util.HttpConnectionPool;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import okhttp3.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class MicrosoftAuth {

    private static final Logger log = LoggerFactory.getLogger(MicrosoftAuth.class);
    private static final Gson GSON = new Gson();

    public static final String DEFAULT_CLIENT_ID = "f293d1a4-3236-4de0-b8b5-cb59d214ea3b";

    private static final String DEVICECODE_URL =
            "https://login.microsoftonline.com/consumers/oauth2/v2.0/devicecode";
    private static final String TOKEN_URL =
            "https://login.microsoftonline.com/consumers/oauth2/v2.0/token";
    private static final String SCOPE = "XboxLive.signin offline_access";

    private static final String XBL_URL = "https://user.auth.xboxlive.com/user/authenticate";
    private static final String XSTS_URL = "https://xsts.auth.xboxlive.com/xsts/authorize";
    private static final String MC_LOGIN_URL =
            "https://api.minecraftservices.com/authentication/login_with_xbox";
    private static final String MC_PROFILE_URL =
            "https://api.minecraftservices.com/minecraft/profile";

    private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");
    private static final MediaType PNG = MediaType.get("image/png");

    private final OkHttpClient http = HttpConnectionPool.getInstance().getClient();

    public static class AuthException extends Exception {
        public AuthException(String m) { super(m); }
    }

    public record MsTokens(String accessToken, String refreshToken, long expiresIn) {}

    public record DeviceCode(String deviceCode, String userCode, String verificationUri,
                             int interval, int expiresIn, String message) {}

    public record MinecraftSession(String uuid, String name, String accessToken,
                                   long expiresIn, String xuid, String skinUrl) {}

    public record ProfileSkin(String id, String url, String variant, String state) {}
    public record ProfileCape(String id, String url, String alias, String state) {}
    public record Profile(String uuid, String name,
                          List<ProfileSkin> skins, List<ProfileCape> capes) {}

    private record Http(int code, String body, JsonObject json) {}

    public static String clientId() {
        String env = System.getenv("F24_MS_CLIENT_ID");
        if (env != null && !env.isBlank()) return env.trim();
        try {
            Path f = LauncherPaths.root().resolve("launcher.json");
            if (Files.exists(f) && Files.size(f) > 0) {
                JsonObject o = GSON.fromJson(Files.readString(f), JsonObject.class);
                if (o != null && o.has("msClientId") && !o.get("msClientId").isJsonNull()) {
                    String v = o.get("msClientId").getAsString();
                    if (v != null && !v.isBlank()) return v.trim();
                }
            }
        } catch (Exception ignored) {}
        return DEFAULT_CLIENT_ID == null ? "" : DEFAULT_CLIENT_ID.trim();
    }

    public static boolean clientIdConfigured() {
        return !clientId().isEmpty();
    }

    public DeviceCode startDeviceCode() throws AuthException {
        String cid = clientId();
        if (cid.isEmpty()) throw new AuthException("Falta el client_id de Microsoft (no configurado).");
        log.info("[MSAUTH] client_id={} endpoint=/consumers scope='{}'", cid, SCOPE);
        Http r = call("devicecode", new Request.Builder().url(DEVICECODE_URL)
                .header("User-Agent", "F24Launcher/1.0.0")
                .post(new FormBody.Builder().add("client_id", cid).add("scope", SCOPE).build()).build());
        if (r.code != 200 || !r.json.has("user_code")) {
            throw new AuthException("Fallo al pedir device code (HTTP " + r.code + "): " + r.body);
        }
        return new DeviceCode(
                str(r.json, "device_code"), str(r.json, "user_code"), str(r.json, "verification_uri"),
                r.json.has("interval") ? r.json.get("interval").getAsInt() : 5,
                r.json.has("expires_in") ? r.json.get("expires_in").getAsInt() : 900,
                str(r.json, "message"));
    }

    public MsTokens pollForTokens(DeviceCode dc) throws AuthException {
        long deadline = System.currentTimeMillis() + dc.expiresIn() * 1000L;
        int interval = Math.max(2, dc.interval());
        while (System.currentTimeMillis() < deadline) {
            try { Thread.sleep(interval * 1000L); }
            catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new AuthException("Login cancelado"); }

            Http r = call("token-poll", new Request.Builder().url(TOKEN_URL)
                    .header("User-Agent", "F24Launcher/1.0.0")
                    .post(new FormBody.Builder()
                            .add("grant_type", "urn:ietf:params:oauth:grant-type:device_code")
                            .add("client_id", clientId())
                            .add("device_code", dc.deviceCode()).build()).build());
            if (r.json.has("access_token")) {
                log.info("[MSAUTH] MS access_token len={} prefix={} refresh={} expires_in={}",
                        len(str(r.json, "access_token")), prefix(str(r.json, "access_token")),
                        r.json.has("refresh_token"),
                        r.json.has("expires_in") ? r.json.get("expires_in").getAsLong() : -1);
                return new MsTokens(str(r.json, "access_token"), str(r.json, "refresh_token"),
                        r.json.has("expires_in") ? r.json.get("expires_in").getAsLong() : 3600);
            }
            String err = str(r.json, "error");
            switch (err == null ? "" : err) {
                case "authorization_pending" -> {}
                case "slow_down" -> interval += 2;
                case "authorization_declined" -> throw new AuthException("Has cancelado el inicio de sesión.");
                case "expired_token" -> throw new AuthException("El código caducó. Inténtalo de nuevo.");
                default -> throw new AuthException("Error de autenticación (HTTP " + r.code + "): " + r.body);
            }
        }
        throw new AuthException("Tiempo de espera agotado. Inténtalo de nuevo.");
    }

    public MsTokens refresh(String refreshToken) throws AuthException {
        Http r = call("token-refresh", new Request.Builder().url(TOKEN_URL)
                .header("User-Agent", "F24Launcher/1.0.0")
                .post(new FormBody.Builder()
                        .add("client_id", clientId())
                        .add("refresh_token", refreshToken)
                        .add("grant_type", "refresh_token")
                        .add("scope", SCOPE).build()).build());
        String at = str(r.json, "access_token");
        if (at == null) throw new AuthException("No se pudo refrescar la sesión (HTTP " + r.code + "): " + r.body);
        return new MsTokens(at, str(r.json, "refresh_token"),
                r.json.has("expires_in") ? r.json.get("expires_in").getAsLong() : 3600);
    }

    public MinecraftSession completeMinecraftAuth(String msAccessToken) throws AuthException {
        Map<String, Object> xblProps = new LinkedHashMap<>();
        xblProps.put("AuthMethod", "RPS");
        xblProps.put("SiteName", "user.auth.xboxlive.com");
        xblProps.put("RpsTicket", "d=" + msAccessToken);
        Http xbl = call("xbl-authenticate", jsonReq(XBL_URL, json(Map.of(
                "Properties", xblProps, "RelyingParty", "http://auth.xboxlive.com", "TokenType", "JWT"))));
        String xblToken = str(xbl.json, "Token");
        String uhs = userHash(xbl.json);
        if (xblToken == null || uhs == null) {
            throw new AuthException("Xbox Live falló (HTTP " + xbl.code + "): " + xbl.body);
        }

        Map<String, Object> xstsProps = new LinkedHashMap<>();
        xstsProps.put("SandboxId", "RETAIL");
        xstsProps.put("UserTokens", new String[]{xblToken});
        Http xsts = call("xsts-authorize", jsonReq(XSTS_URL, json(Map.of(
                "Properties", xstsProps, "RelyingParty", "rp://api.minecraftservices.com/", "TokenType", "JWT"))));
        if (xsts.json.has("XErr")) {
            long code = xsts.json.get("XErr").getAsLong();
            throw new AuthException(switch ((int) code) {
                case (int) 2148916233L -> "Esta cuenta Microsoft no tiene un perfil de Xbox. Crea uno y reintenta.";
                case (int) 2148916235L -> "Xbox Live no está disponible en la región de la cuenta.";
                case (int) 2148916238L -> "Cuenta infantil: debe añadirse a una familia para usar Xbox Live.";
                default -> "Xbox Live (XSTS) rechazó la sesión (XErr " + code + "): " + xsts.body;
            });
        }
        String xstsToken = str(xsts.json, "Token");
        String xuid = xuid(xsts.json);
        if (xstsToken == null) throw new AuthException("XSTS falló (HTTP " + xsts.code + "): " + xsts.body);

        Http mc = call("login_with_xbox", jsonReq(MC_LOGIN_URL, json(Map.of(
                "identityToken", "XBL3.0 x=" + uhs + ";" + xstsToken))));
        String mcToken = str(mc.json, "access_token");
        if (mcToken == null) {
            throw new AuthException("login_with_xbox falló (HTTP " + mc.code + "): " + mc.body);
        }
        long expiresIn = mc.json.has("expires_in") ? mc.json.get("expires_in").getAsLong() : 86400;

        Http prof = call("minecraft-profile", new Request.Builder().url(MC_PROFILE_URL)
                .header("Authorization", "Bearer " + mcToken)
                .header("User-Agent", "F24Launcher/1.0.0").get().build());
        if (prof.code == 404) throw new AuthException("Esta cuenta no posee Minecraft: Java Edition.");
        if (prof.code != 200 || str(prof.json, "id") == null) {
            throw new AuthException("minecraft/profile falló (HTTP " + prof.code + "): " + prof.body);
        }
        return new MinecraftSession(str(prof.json, "id"), str(prof.json, "name"), mcToken,
                expiresIn, xuid, skinUrl(prof.json));
    }

    // ── Skins y capas (Minecraft Services API; requiere el mcAccessToken) ──

    /** Lee el perfil completo (skins y capas) de la cuenta. */
    public Profile fetchProfile(String mcAccessToken) throws AuthException {
        Http r = call("profile-skins", new Request.Builder().url(MC_PROFILE_URL)
                .header("Authorization", "Bearer " + mcAccessToken)
                .header("User-Agent", "F24Launcher/1.0.0").get().build());
        if (r.code != 200 || str(r.json, "id") == null) {
            throw new AuthException("No se pudo leer el perfil (HTTP " + r.code + "): " + r.body);
        }
        return parseProfile(r.json);
    }

    /**
     * Cambia la skin desde una URL pública (variant: classic|slim). Descarga los
     * bytes y los sube como multipart (la API de cambio-por-URL de Mojang es poco
     * fiable y rechaza hosts no confiables; subir el PNG funciona siempre).
     */
    public Profile changeSkinUrl(String mcAccessToken, String url, String variant) throws AuthException {
        return uploadSkin(mcAccessToken, downloadPng(url), "skin.png", variant);
    }

    private byte[] downloadPng(String url) throws AuthException {
        Request req = new Request.Builder().url(url)
                .header("User-Agent", "F24Launcher/1.0.0").get().build();
        try (Response r = http.newCall(req).execute()) {
            if (r.code() != 200 || r.body() == null) {
                throw new AuthException("No se pudo descargar la skin (HTTP " + r.code() + ").");
            }
            return r.body().bytes();
        } catch (IOException e) {
            throw new AuthException("Error al descargar la skin: " + e.getMessage());
        }
    }

    /** Sube una skin desde un PNG (variant: classic|slim). */
    public Profile uploadSkin(String mcAccessToken, byte[] png, String fileName, String variant)
            throws AuthException {
        RequestBody body = new MultipartBody.Builder().setType(MultipartBody.FORM)
                .addFormDataPart("variant", variant(variant))
                .addFormDataPart("file", fileName == null || fileName.isBlank() ? "skin.png" : fileName,
                        RequestBody.create(png, PNG))
                .build();
        Http r = call("skin-upload", new Request.Builder().url(MC_PROFILE_URL + "/skins")
                .header("Authorization", "Bearer " + mcAccessToken)
                .header("User-Agent", "F24Launcher/1.0.0").post(body).build());
        return profileResult(r, "Subir skin");
    }

    /** Restablece la skin por defecto (Steve/Alex). */
    public Profile resetSkin(String mcAccessToken) throws AuthException {
        Http r = call("skin-reset", new Request.Builder().url(MC_PROFILE_URL + "/skins/active")
                .header("Authorization", "Bearer " + mcAccessToken)
                .header("User-Agent", "F24Launcher/1.0.0").delete().build());
        return profileResult(r, "Restablecer skin");
    }

    /** Activa una capa por su id. */
    public Profile showCape(String mcAccessToken, String capeId) throws AuthException {
        Http r = call("cape-show", new Request.Builder().url(MC_PROFILE_URL + "/capes/active")
                .header("Authorization", "Bearer " + mcAccessToken)
                .header("User-Agent", "F24Launcher/1.0.0")
                .put(RequestBody.create(json(Map.of("capeId", capeId)), JSON)).build());
        return profileResult(r, "Activar capa");
    }

    /** Oculta la capa activa. */
    public Profile hideCape(String mcAccessToken) throws AuthException {
        Http r = call("cape-hide", new Request.Builder().url(MC_PROFILE_URL + "/capes/active")
                .header("Authorization", "Bearer " + mcAccessToken)
                .header("User-Agent", "F24Launcher/1.0.0").delete().build());
        return profileResult(r, "Ocultar capa");
    }

    private Profile profileResult(Http r, String what) throws AuthException {
        if (r.code != 200 || str(r.json, "id") == null) {
            throw new AuthException(what + " falló (HTTP " + r.code + "): " + r.body);
        }
        return parseProfile(r.json);
    }

    private static String variant(String v) {
        return "slim".equalsIgnoreCase(v) ? "slim" : "classic";
    }

    private static Profile parseProfile(JsonObject o) {
        List<ProfileSkin> skins = new ArrayList<>();
        if (o.has("skins") && o.get("skins").isJsonArray()) {
            JsonArray arr = o.getAsJsonArray("skins");
            for (int i = 0; i < arr.size(); i++) {
                JsonObject s = arr.get(i).getAsJsonObject();
                skins.add(new ProfileSkin(str(s, "id"), str(s, "url"),
                        str(s, "variant"), str(s, "state")));
            }
        }
        List<ProfileCape> capes = new ArrayList<>();
        if (o.has("capes") && o.get("capes").isJsonArray()) {
            JsonArray arr = o.getAsJsonArray("capes");
            for (int i = 0; i < arr.size(); i++) {
                JsonObject c = arr.get(i).getAsJsonObject();
                capes.add(new ProfileCape(str(c, "id"), str(c, "url"),
                        str(c, "alias"), str(c, "state")));
            }
        }
        return new Profile(str(o, "id"), str(o, "name"), skins, capes);
    }

    private Request jsonReq(String url, String body) {
        return new Request.Builder().url(url)
                .header("User-Agent", "F24Launcher/1.0.0")
                .header("Accept", "application/json")
                .post(RequestBody.create(body, JSON)).build();
    }

    private Http call(String name, Request req) throws AuthException {
        try (Response res = http.newCall(req).execute()) {
            String body = res.body() != null ? res.body().string() : "";
            log.info("[MSAUTH] {} -> HTTP {} | {}", name, res.code(), body.isBlank() ? "(sin cuerpo)" : body);
            JsonObject j = null;
            try { j = body.isBlank() ? new JsonObject() : GSON.fromJson(body, JsonObject.class); }
            catch (Exception ignored) {}
            return new Http(res.code(), body, j == null ? new JsonObject() : j);
        } catch (IOException e) {
            log.warn("[MSAUTH] {} error de red: {}", name, e.getMessage());
            throw new AuthException("Error de red en " + name + ": " + e.getMessage());
        }
    }

    private static String str(JsonObject o, String k) {
        return (o != null && o.has(k) && !o.get(k).isJsonNull()) ? o.get(k).getAsString() : null;
    }

    private static int len(String s) { return s == null ? 0 : s.length(); }
    private static String prefix(String s) { return s == null ? "" : s.substring(0, Math.min(12, s.length())); }

    private static String userHash(JsonObject o) {
        try {
            return o.getAsJsonObject("DisplayClaims").getAsJsonArray("xui")
                    .get(0).getAsJsonObject().get("uhs").getAsString();
        } catch (Exception e) { return null; }
    }

    private static String xuid(JsonObject o) {
        try {
            return o.getAsJsonObject("DisplayClaims").getAsJsonArray("xui")
                    .get(0).getAsJsonObject().get("xid").getAsString();
        } catch (Exception e) { return null; }
    }

    private static String skinUrl(JsonObject profile) {
        try {
            var skins = profile.getAsJsonArray("skins");
            for (int i = 0; i < skins.size(); i++) {
                JsonObject sk = skins.get(i).getAsJsonObject();
                if ("ACTIVE".equalsIgnoreCase(str(sk, "state"))) return str(sk, "url");
            }
            if (skins.size() > 0) return str(skins.get(0).getAsJsonObject(), "url");
        } catch (Exception ignored) {}
        return null;
    }

    private static String json(Map<String, Object> m) { return GSON.toJson(m); }
}
