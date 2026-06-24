package f24launcher.core.runtime;

import f24launcher.core.LauncherPaths;
import f24launcher.instance.InstanceConfig;
import f24launcher.util.DownloadManager;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.nio.file.*;
import java.util.UUID;
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
        log.info("Descargando Temurin JRE {} desde {}", feature, url);
        long t0 = System.nanoTime();

        // Descarga en streaming a un .zip temporal (sin cargar ~45 MB en memoria), y
        // extracción atómica a un temporal que solo se promueve si quedó completo (con
        // javaw.exe). Así un fallo o un corte a mitad NO deja runtime ni zip a medias.
        Path zipFile = dest.resolveSibling(dest.getFileName() + ".dl-" + UUID.randomUUID() + ".zip");
        Path tmp = dest.resolveSibling(dest.getFileName() + ".tmp-" + UUID.randomUUID());
        try {
            DownloadManager.Result r = DownloadManager.getInstance()
                    .download(new DownloadManager.Task(url, zipFile, 0, null, null), false);
            if (!r.ok())
                throw new IOException("No se pudo descargar el JRE Temurin " + feature, r.error());
            log.info("JRE {} descargado: {} MB en {} ms", feature, Files.size(zipFile) / (1024 * 1024),
                    (System.nanoTime() - t0) / 1_000_000);

            deleteRecursive(tmp);
            Files.createDirectories(tmp);
            try (ZipInputStream zis = new ZipInputStream(
                    new BufferedInputStream(Files.newInputStream(zipFile)))) {
                ZipEntry e;
                while ((e = zis.getNextEntry()) != null) {
                    Path out = tmp.resolve(e.getName()).normalize();
                    if (!out.startsWith(tmp)) continue; // protección zip-slip
                    if (e.isDirectory()) {
                        Files.createDirectories(out);
                    } else {
                        Files.createDirectories(out.getParent());
                        Files.copy(zis, out, StandardCopyOption.REPLACE_EXISTING);
                    }
                }
            }
            if (findJavaw(tmp) == null)
                throw new IOException("El JRE Temurin " + feature + " descargado no contiene javaw.exe");

            // Reemplaza el destino de forma atómica (mismo volumen → rename de carpeta).
            deleteRecursive(dest);
            try {
                Files.move(tmp, dest, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException ex) {
                Files.move(tmp, dest);
            }
            log.info("Temurin JRE {} instalado en {}", feature, dest);
        } finally {
            deleteRecursive(tmp); // limpia el temporal de extracción si algo falló
            try { Files.deleteIfExists(zipFile); } catch (Exception ignored) {}
        }
    }

    /** Borra un árbol de archivos/directorios si existe (best-effort). */
    private void deleteRecursive(Path root) {
        if (!Files.exists(root)) return;
        try (Stream<Path> walk = Files.walk(root)) {
            walk.sorted(java.util.Comparator.reverseOrder()).forEach(p -> {
                try { Files.deleteIfExists(p); } catch (Exception ignored) {}
            });
        } catch (Exception ignored) {}
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
