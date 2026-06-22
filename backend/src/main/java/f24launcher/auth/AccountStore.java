package f24launcher.auth;

import f24launcher.core.LauncherPaths;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Type;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Persiste las cuentas en <code>%APPDATA%\F24Launcher\accounts.json</code>.
 * Los secretos de las cuentas Microsoft (mcAccessToken / refreshToken) se cifran
 * con {@link Crypto} antes de escribir y se descifran al leer.
 */
public class AccountStore {

    private static final Logger log = LoggerFactory.getLogger(AccountStore.class);
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Type LIST_TYPE = new TypeToken<List<StoredAccount>>() {}.getType();

    private final List<StoredAccount> accounts = new ArrayList<>();

    public AccountStore() { load(); }

    private Path file() { return LauncherPaths.root().resolve("accounts.json"); }

    private synchronized void load() {
        accounts.clear();
        Path f = file();
        try {
            if (Files.exists(f) && Files.size(f) > 0) {
                List<StoredAccount> raw = GSON.fromJson(Files.readString(f), LIST_TYPE);
                if (raw != null) {
                    for (StoredAccount a : raw) {
                        a.mcAccessToken = Crypto.decrypt(a.mcAccessToken);
                        a.refreshToken = Crypto.decrypt(a.refreshToken);
                        accounts.add(a);
                    }
                }
            }
        } catch (Exception e) {
            log.warn("No se pudo leer accounts.json: {}", e.getMessage());
        }
    }

    private synchronized void persist() {
        try {
            List<StoredAccount> enc = new ArrayList<>();
            for (StoredAccount a : accounts) {
                StoredAccount c = copy(a);
                c.mcAccessToken = Crypto.encrypt(a.mcAccessToken);
                c.refreshToken = Crypto.encrypt(a.refreshToken);
                enc.add(c);
            }
            Files.writeString(file(), GSON.toJson(enc, LIST_TYPE));
        } catch (Exception e) {
            log.warn("No se pudo guardar accounts.json: {}", e.getMessage());
        }
    }

    private static StoredAccount copy(StoredAccount a) {
        StoredAccount c = new StoredAccount();
        c.id = a.id; c.type = a.type; c.username = a.username; c.uuid = a.uuid;
        c.xuid = a.xuid; c.skinUrl = a.skinUrl; c.mcAccessToken = a.mcAccessToken;
        c.refreshToken = a.refreshToken; c.expiresAt = a.expiresAt; c.active = a.active;
        return c;
    }

    public synchronized List<StoredAccount> list() { return new ArrayList<>(accounts); }

    public synchronized StoredAccount get(String id) {
        return accounts.stream().filter(a -> a.id.equals(id)).findFirst().orElse(null);
    }

    public synchronized StoredAccount getActive() {
        return accounts.stream().filter(a -> a.active).findFirst()
                .orElse(accounts.isEmpty() ? null : accounts.get(0));
    }

    /** Inserta o reemplaza por id. La primera cuenta añadida queda activa. */
    public synchronized StoredAccount upsert(StoredAccount acc) {
        accounts.removeIf(a -> a.id.equals(acc.id));
        if (accounts.isEmpty()) acc.active = true;
        accounts.add(acc);
        if (acc.active) accounts.forEach(a -> a.active = a.id.equals(acc.id));
        persist();
        return acc;
    }

    public synchronized void setActive(String id) {
        boolean found = accounts.stream().anyMatch(a -> a.id.equals(id));
        if (!found) return;
        accounts.forEach(a -> a.active = a.id.equals(id));
        persist();
    }

    public synchronized boolean remove(String id) {
        boolean wasActive = accounts.stream().anyMatch(a -> a.id.equals(id) && a.active);
        boolean removed = accounts.removeIf(a -> a.id.equals(id));
        if (removed && wasActive && !accounts.isEmpty()) accounts.get(0).active = true;
        if (removed) persist();
        return removed;
    }

    public synchronized void save() { persist(); }
}
