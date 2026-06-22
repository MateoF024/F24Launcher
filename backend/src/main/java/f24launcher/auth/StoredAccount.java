package f24launcher.auth;

/**
 * Cuenta persistida en accounts.json. Para cuentas Microsoft, los tokens se
 * guardan cifrados (ver {@link AccountStore}); para offline solo nombre + uuid.
 */
public class StoredAccount {
    public String id;            // microsoft: uuid; offline: "offline:<nombre>"
    public String type;          // "microsoft" | "offline"
    public String username;
    public String uuid;
    public String xuid;          // microsoft
    public String skinUrl;       // microsoft
    public String mcAccessToken; // microsoft (cifrado en disco)
    public String refreshToken;  // microsoft (cifrado en disco)
    public long expiresAt;       // epoch ms de caducidad del mcAccessToken
    public boolean active;

    public boolean isMicrosoft() { return "microsoft".equals(type); }

    public boolean tokenExpired() {
        // margen de 60s para evitar lanzar con un token a punto de caducar
        return System.currentTimeMillis() >= (expiresAt - 60_000L);
    }
}
