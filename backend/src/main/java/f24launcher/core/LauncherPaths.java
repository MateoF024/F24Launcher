package f24launcher.core;

import f24launcher.settings.AppSettings;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Rutas del launcher. Layout con caché compartida entre instancias:
 *
 * <pre>
 * %APPDATA%\F24Launcher\
 * ├─ launcher.json  accounts.json  backend.log
 * ├─ assets\ libraries\ versions\ runtimes\ loaders\   # COMPARTIDOS
 * ├─ natives\<versionKey>\                              # natives por versión (mc + loader)
 * ├─ instances\<id>\                                    # carpeta de juego directa (mods, saves, config…)
 * └─ instances-data\<id>\ ( instance.json · content.json )
 * </pre>
 *
 * Las instancias contienen únicamente su contenido de juego; los metadatos
 * (instance.json, content.json) viven aparte en instances-data y los natives se
 * comparten por versión en natives/.
 */
public final class LauncherPaths {

    private LauncherPaths() {}

    public static Path root() {
        Path p = Paths.get(System.getProperty("user.home"), "AppData", "Roaming", "F24Launcher");
        return ensure(p);
    }

    public static Path assets()    { return ensure(root().resolve("assets")); }
    public static Path assetIndexes() { return ensure(assets().resolve("indexes")); }
    public static Path assetObjects() { return ensure(assets().resolve("objects")); }
    public static Path libraries() { return ensure(root().resolve("libraries")); }
    public static Path versions()  { return ensure(root().resolve("versions")); }
    public static Path runtimes()  { return ensure(root().resolve("runtimes")); }
    /**
     * Carpeta de instancias (juego). Por defecto root()/instances, pero puede
     * reubicarse globalmente desde Ajustes (AppSettings.instancesPath).
     */
    public static Path instances() {
        String custom = AppSettings.getInstance().getInstancesPath();
        if (custom != null && !custom.isBlank()) return ensure(Paths.get(custom));
        return ensure(root().resolve("instances"));
    }

    public static Path instancesData() { return ensure(root().resolve("instances-data")); }
    public static Path natives()   { return ensure(root().resolve("natives")); }

    /** versions/<id>/<id>.json */
    public static Path versionJson(String id) {
        return ensure(versions().resolve(id)).resolve(id + ".json");
    }

    /** versions/<id>/<id>.jar */
    public static Path versionJar(String id) {
        return ensure(versions().resolve(id)).resolve(id + ".jar");
    }

    /** libraries/<maven/path> */
    public static Path library(String relativePath) {
        return libraries().resolve(relativePath);
    }

    /** instances/<id> — carpeta de juego (contiene mods, saves, config… directamente). */
    public static Path instanceDir(String id)   { return instances().resolve(id); }
    public static Path instanceGameDir(String id) { return ensure(instanceDir(id)); }

    /** instances-data/<id> — metadatos (instance.json, content.json) separados del juego. */
    public static Path instanceData(String id) { return ensure(instancesData().resolve(id)); }

    /** instances-data/<id>/icon.png — icono personalizado de la instancia (256x256). */
    public static Path instanceIcon(String id) { return instanceData(id).resolve("icon.png"); }

    /** natives/<versionKey> — natives compartidos por versión (mc + loader). */
    public static Path versionNatives(String versionKey) {
        return ensure(natives().resolve(safe(versionKey)));
    }

    private static String safe(String name) {
        String s = name == null ? "" : name.replaceAll("[\\\\/:*?\"<>|]", "_").trim();
        return s.isEmpty() ? "unknown" : s;
    }

    private static Path ensure(Path p) {
        try { Files.createDirectories(p); } catch (Exception ignored) {}
        return p;
    }
}
