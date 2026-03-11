package bundle.util;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;

public enum OperatingSystem {
    WINDOWS, MAC, LINUX, UNKNOWN;

    private static final Logger log = LoggerFactory.getLogger(OperatingSystem.class);

    public Path getMCDir() {
        Path home = Path.of(System.getProperty("user.home"));

        Path mcDir = switch (this) {
            case WINDOWS -> home.resolve("AppData").resolve("Roaming").resolve(".minecraft");
            case MAC     -> home.resolve("Library").resolve("Application Support").resolve("minecraft");
            case LINUX   -> home.resolve(".minecraft");
            case UNKNOWN -> {
                log.warn("Sistema operativo no reconocido. Usando directorio home como fallback: {}", home);
                yield home;
            }
        };

        if (!Files.exists(mcDir)) {
            log.warn("Directorio de Minecraft no encontrado en '{}'. Usando home como fallback.", mcDir);
            return home;
        }

        return mcDir;
    }

    public static OperatingSystem getCurrent() {
        String osName = System.getProperty("os.name").toLowerCase();

        if (osName.contains("win"))                                          return WINDOWS;
        if (osName.contains("mac"))                                          return MAC;
        if (osName.contains("linux") || osName.contains("nix")
                || osName.contains("nux") || osName.contains("unix"))        return LINUX;

        log.warn("Sistema operativo no identificado: '{}'", osName);
        return UNKNOWN;
    }
}