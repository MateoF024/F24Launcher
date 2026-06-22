package f24launcher.auth;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

/**
 * Modo offline: genera un UUID determinístico a partir del nombre (igual que un
 * servidor con online-mode=false) y un accessToken placeholder. Requiere poseer
 * el juego; no permite acceso a servidores premium.
 */
public final class OfflineAuth {

    private OfflineAuth() {}

    public static Account create(String username) {
        String name = (username == null || username.isBlank()) ? "Player" : username.trim();
        UUID uuid = UUID.nameUUIDFromBytes(("OfflinePlayer:" + name).getBytes(StandardCharsets.UTF_8));
        return Account.offline(name, uuid.toString().replace("-", ""));
    }
}
