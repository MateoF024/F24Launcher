package f24launcher.instance;

import com.google.gson.annotations.Expose;

/**
 * Configuración de una instancia nativa de F24Launcher (instance.json).
 */
public class InstanceConfig {
    @Expose public String id;
    @Expose public String name;
    @Expose public String mcVersion;
    @Expose public String loader = "vanilla";        // vanilla | fabric | quilt | neoforge | forge
    @Expose public String loaderVersion = "";
    @Expose public String javaPathOverride = "";       // vacío = runtime gestionado/auto
    @Expose public int minMemoryMb = 1024;
    @Expose public int maxMemoryMb = 4096;
    @Expose public int windowWidth = 854;
    @Expose public int windowHeight = 480;
    @Expose public boolean fullscreen = false;
    @Expose public String jvmArgs = "";
    @Expose public long lastPlayed = 0;
    @Expose public long totalPlayMs = 0;
    @Expose public String sourceModpackId = "";        // id del modpack si vino de uno
    @Expose public boolean installed = false;            // true cuando la versión/loader ya está descargado

    public InstanceConfig() {}

    public InstanceConfig(String id, String name, String mcVersion, String loader, String loaderVersion) {
        this.id = id;
        this.name = name;
        this.mcVersion = mcVersion;
        if (loader != null && !loader.isEmpty()) this.loader = loader;
        this.loaderVersion = loaderVersion != null ? loaderVersion : "";
    }

    /**
     * Clave de versión usada para compartir natives entre instancias: la versión
     * de MC, y si hay loader, también su tipo y versión. P. ej. {@code 1.20.1} o
     * {@code 1.20.1-fabric-0.15.0}.
     */
    public String versionKey() {
        if (loader == null || loader.isBlank() || "vanilla".equals(loader)) return mcVersion;
        String lv = (loaderVersion == null || loaderVersion.isBlank()) ? "" : "-" + loaderVersion;
        return mcVersion + "-" + loader + lv;
    }
}
