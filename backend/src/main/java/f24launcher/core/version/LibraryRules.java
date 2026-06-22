package f24launcher.core.version;

import f24launcher.core.meta.MojangMeta.*;

import java.util.List;

/** Lógica compartida para evaluar librerías en Windows x64 (reglas + natives). */
public final class LibraryRules {

    private LibraryRules() {}

    /** Clasificador maven del nombre (group:artifact:version:classifier), o null. */
    public static String classifier(Library lib) {
        if (lib.name == null) return null;
        String[] parts = lib.name.split(":");
        return parts.length >= 4 ? parts[3] : null;
    }

    /** Evalúa las reglas OS de una librería para Windows. */
    public static boolean allowedOnWindows(List<Rule> rules) {
        if (rules == null || rules.isEmpty()) return true;
        boolean allowed = false;
        for (Rule r : rules) {
            boolean matches = r.os == null || r.os.name == null || r.os.name.equals("windows");
            if ("allow".equals(r.action) && matches) allowed = true;
            else if ("disallow".equals(r.action) && matches) allowed = false;
        }
        return allowed;
    }

    /**
     * ¿Se usa esta librería en Windows x64? Filtra por reglas y, si es una lib de
     * natives con clasificador, acepta SOLO {@code natives-windows} (x64),
     * descartando -arm64, -x86, -linux, -macos.
     */
    public static boolean usableOnWindows(Library lib) {
        if (!allowedOnWindows(lib.rules)) return false;
        String c = classifier(lib);
        if (c != null && c.startsWith("natives-")) {
            return c.equals("natives-windows");
        }
        return true;
    }

    /** ¿Es la librería de natives a extraer en Windows x64? */
    public static boolean isWindowsNative(Library lib) {
        String c = classifier(lib);
        if (c != null && c.startsWith("natives-")) {
            return c.equals("natives-windows");      // moderno (>=1.19): solo x64
        }
        return lib.natives != null && lib.natives.containsKey("windows"); // clásico (<1.19)
    }

    /** Artefacto de natives clásico (&lt;1.19) para Windows, si aplica. */
    public static Artifact classicNativeArtifact(Library lib) {
        if (lib.natives == null || lib.downloads == null || lib.downloads.classifiers == null) return null;
        String classifier = lib.natives.get("windows");
        if (classifier == null) return null;
        classifier = classifier.replace("${arch}", "64");
        return lib.downloads.classifiers.get(classifier);
    }

    /** Jar de natives a extraer (moderno: artifact x64; clásico: classifier). */
    public static Artifact nativeJar(Library lib) {
        if ("natives-windows".equals(classifier(lib))
                && lib.downloads != null && lib.downloads.artifact != null) {
            return lib.downloads.artifact;
        }
        return classicNativeArtifact(lib);
    }
}
