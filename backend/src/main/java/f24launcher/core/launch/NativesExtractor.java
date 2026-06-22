package f24launcher.core.launch;

import f24launcher.core.LauncherPaths;
import f24launcher.core.meta.MojangMeta.*;
import f24launcher.core.version.LibraryRules;
import f24launcher.instance.InstanceConfig;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.InputStream;
import java.nio.file.*;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Extrae las librerías nativas (.dll) de la versión a natives/&lt;versionKey&gt;/,
 * compartido entre todas las instancias de la misma versión + loader.
 */
public class NativesExtractor {

    private static final Logger log = LoggerFactory.getLogger(NativesExtractor.class);

    public void extract(InstanceConfig cfg, VersionDetails v) throws Exception {
        Path nativesDir = LauncherPaths.versionNatives(cfg.versionKey());
        for (Library lib : v.libraries) {
            if (!LibraryRules.usableOnWindows(lib)) continue;
            if (!LibraryRules.isWindowsNative(lib)) continue;
            Artifact jar = LibraryRules.nativeJar(lib);
            if (jar == null || jar.path == null) continue;
            Path jarPath = LauncherPaths.library(jar.path);
            if (!Files.exists(jarPath)) continue;
            unzipNatives(jarPath, nativesDir);
        }
    }

    private void unzipNatives(Path jar, Path dest) throws Exception {
        try (ZipFile zip = new ZipFile(jar.toFile())) {
            var entries = zip.entries();
            while (entries.hasMoreElements()) {
                ZipEntry e = entries.nextElement();
                if (e.isDirectory()) continue;
                String name = e.getName();
                if (name.startsWith("META-INF/") || !name.toLowerCase().endsWith(".dll")) continue;
                Path out = dest.resolve(Paths.get(name).getFileName().toString());
                try (InputStream is = zip.getInputStream(e)) {
                    Files.copy(is, out, StandardCopyOption.REPLACE_EXISTING);
                }
            }
        }
        log.debug("Natives extraídos de {}", jar.getFileName());
    }
}
