package f24launcher.auth;

/** Datos de sesión usados al lanzar el juego. */
public record Account(String username, String uuid, String accessToken, String userType, String xuid) {

    /** Cuenta offline (sin xuid). */
    public static Account offline(String username, String uuid) {
        return new Account(username, uuid, "0", "legacy", "");
    }
}
