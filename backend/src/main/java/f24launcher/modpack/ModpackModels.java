package f24launcher.modpack;

/** DTOs del catálogo de modpacks privados. */
public final class ModpackModels {

    private ModpackModels() {}

    /**
     * Un modpack publicado en installer_config.json. {@code mcVersion}/{@code loader}
     * pueden venir vacíos si el descriptor solo trae la URL: en ese caso se deducen
     * del propio archivo del modpack al instalar.
     *
     * <p>Variantes (0.0.5): cada modpack puede ofrecer dos descargas del mismo
     * contenido, {@code urlStandard} (estándar) y {@code urlLite} (ligera). El
     * descriptor legado con un único campo {@code url} se interpreta como estándar y
     * deja {@code urlLite} vacío. {@code version} permite detectar actualizaciones.</p>
     */
    public record Modpack(
            String id, String name, String version,
            String urlStandard, String urlLite, String format,
            String mcVersion, String loader, String loaderVersion,
            String icon, String summary, String descriptionUrl) {

        /** ¿Tiene variante LITE disponible? (controla si se muestra el selector). */
        public boolean hasLite() { return urlLite != null && !urlLite.isBlank(); }

        /** URL de una variante concreta ("lite" → ligera; cualquier otra → estándar). */
        public String urlFor(String variant) {
            return ("lite".equalsIgnoreCase(variant) && hasLite()) ? urlLite : urlStandard;
        }
    }
}
