package f24launcher.modpack;

/** DTOs del catálogo de modpacks privados. */
public final class ModpackModels {

    private ModpackModels() {}

    /**
     * Un modpack publicado en installer_config.json. {@code mcVersion}/{@code loader}
     * pueden venir vacíos si el descriptor solo trae la URL: en ese caso se deducen
     * del propio archivo del modpack al instalar.
     */
    public record Modpack(
            String id, String name, String downloadUrl, String format,
            String mcVersion, String loader, String loaderVersion,
            String icon, String summary, String descriptionUrl) {}
}
