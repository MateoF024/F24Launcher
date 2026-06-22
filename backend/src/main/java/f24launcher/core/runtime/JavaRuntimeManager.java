package f24launcher.core.runtime;

import f24launcher.core.LauncherPaths;
import f24launcher.instance.InstanceConfig;
import f24launcher.util.HttpConnectionPool;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayInputStream;
import java.nio.file.*;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Resuelve (y descarga si hace falta) el JRE adecuado para una instancia.
 *
 * Minecraft exige una versión concreta de Java (8/17/21) y las versiones muy
 * nuevas de Java rompen el juego (p. ej. Java 24+ quitó SecurityManager). Por eso
 * gestionamos JREs propios de Eclipse Temurin (Adoptium) cacheados en runtimes/.
 */
public class JavaRuntimeManager {

    private static final Logger log = LoggerFactory.getLogger(JavaRuntimeManager.class);

    /** Resuelve el ejecutable javaw para la instancia y la versión major requerida. */
    public String resolveJavaExe(InstanceConfig cfg, int requiredMajor) throws Exception {
        if (cfg.javaPathOverride != null && !cfg.javaPathOverride.isBlank()) {
            return cfg.javaPathOverride.trim();
        }
        int feature = mapFeature(requiredMajor);
        Path rt = LauncherPaths.runtimes().resolve("temurin-" + feature);

        Path javaw = findJavaw(rt);
        if (javaw != null) return javaw.toString();

        downloadAndExtract(feature, rt);
        javaw = findJavaw(rt);
        if (javaw != null) return javaw.toString();

        log.warn("No se pudo obtener Temurin {}, usando el Java del sistema (puede fallar).", feature);
        return systemJavaw();
    }

    /** Mapea la versión requerida a un feature release disponible en Adoptium. */
    private int mapFeature(int major) {
        if (major <= 0) return 21;
        if (major <= 8) return 8;
        if (major <= 17) return 17;     // 16/17 → 17 LTS
        if (major <= 21) return 21;     // 18-21 → 21 LTS
        return major;                    // 22+ (p. ej. 25 para MC 26.2) → exacto
    }

    private void downloadAndExtract(int feature, Path dest) throws Exception {
        String url = "https://api.adoptium.net/v3/binary/latest/" + feature
                + "/ga/windows/x64/jre/hotspot/normal/eclipse";
        log.info("Descargando Temurin JRE {} ...", feature);
        byte[] zip = HttpConnectionPool.getInstance().getBytes(url);
        Files.createDirectories(dest);
        try (ZipInputStream zis = new ZipInputStream(new ByteArrayInputStream(zip))) {
            ZipEntry e;
            while ((e = zis.getNextEntry()) != null) {
                Path out = dest.resolve(e.getName()).normalize();
                if (!out.startsWith(dest)) continue; // protección zip-slip
                if (e.isDirectory()) {
                    Files.createDirectories(out);
                } else {
                    Files.createDirectories(out.getParent());
                    Files.copy(zis, out, StandardCopyOption.REPLACE_EXISTING);
                }
            }
        }
        log.info("Temurin JRE {} instalado en {}", feature, dest);
    }

    private Path findJavaw(Path runtimeDir) {
        if (!Files.isDirectory(runtimeDir)) return null;
        try (Stream<Path> walk = Files.walk(runtimeDir)) {
            return walk.filter(p -> p.getFileName().toString().equalsIgnoreCase("javaw.exe"))
                    .findFirst().orElse(null);
        } catch (Exception e) {
            return null;
        }
    }

    private String systemJavaw() {
        String home = System.getProperty("java.home");
        if (home != null) {
            Path javaw = Paths.get(home, "bin", "javaw.exe");
            if (Files.exists(javaw)) return javaw.toString();
        }
        return "javaw";
    }
}
