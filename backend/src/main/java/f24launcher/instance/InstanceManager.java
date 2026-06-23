package f24launcher.instance;

import f24launcher.core.LauncherPaths;
import f24launcher.settings.AppSettings;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.*;
import java.util.*;
import java.util.stream.Stream;

/**
 * Gestiona instancias nativas y aisladas. La carpeta de juego vive en
 * instances/&lt;id&gt;/ (mods, saves, config… directamente, sin .minecraft) y los
 * metadatos (instance.json, content.json) en instances-data/&lt;id&gt;/.
 */
public class InstanceManager {

    private static final Logger log = LoggerFactory.getLogger(InstanceManager.class);
    private static final Gson GSON = new GsonBuilder()
            .excludeFieldsWithoutExposeAnnotation().setPrettyPrinting().create();

    /** Lista las instancias leyendo cada instance.json en instances-data/. */
    public List<InstanceConfig> list() {
        List<InstanceConfig> out = new ArrayList<>();
        try (Stream<Path> dirs = Files.list(LauncherPaths.instancesData())) {
            dirs.filter(Files::isDirectory).forEach(dir -> {
                InstanceConfig cfg = readConfig(dir.resolve("instance.json"));
                if (cfg != null) out.add(cfg);
            });
        } catch (Exception e) {
            log.warn("No se pudieron listar instancias: {}", e.getMessage());
        }
        // Favoritas primero; dentro de cada grupo, las más jugadas arriba.
        out.sort(Comparator.comparing((InstanceConfig c) -> !c.favorite)
                .thenComparing(Comparator.comparingLong((InstanceConfig c) -> c.lastPlayed).reversed()));
        return out;
    }

    public InstanceConfig get(String id) {
        return readConfig(LauncherPaths.instanceData(id).resolve("instance.json"));
    }

    /** Crea una instancia nueva (con los valores por defecto de AppSettings) y devuelve su config. */
    public synchronized InstanceConfig create(String name, String mcVersion,
                                              String loader, String loaderVersion) {
        if (name == null || name.isBlank()) name = "Instancia";
        if (mcVersion == null || mcVersion.isBlank()) {
            throw new IllegalArgumentException("mcVersion requerido");
        }
        String id = uniqueId(slug(name));
        InstanceConfig cfg = new InstanceConfig(id, name.trim(), mcVersion, loader, loaderVersion);
        applyDefaults(cfg);

        LauncherPaths.instanceGameDir(id);              // crea la carpeta de juego
        LauncherPaths.instanceData(id);                 // crea la carpeta de metadatos
        save(cfg);
        log.info("Instancia creada: {} (MC {} · {})", id, mcVersion,
                cfg.loader + (cfg.loaderVersion.isEmpty() ? "" : " " + cfg.loaderVersion));
        return cfg;
    }

    /** Vuelca en la config los valores por defecto configurados en Ajustes. */
    private void applyDefaults(InstanceConfig cfg) {
        AppSettings s = AppSettings.getInstance();
        cfg.minMemoryMb = s.getDefaultMinMemoryMb();
        cfg.maxMemoryMb = s.getDefaultMaxMemoryMb();
        cfg.windowWidth = s.getDefaultWindowWidth();
        cfg.windowHeight = s.getDefaultWindowHeight();
        cfg.jvmArgs = s.getDefaultJvmArgs();
    }

    public synchronized void save(InstanceConfig cfg) {
        Path file = LauncherPaths.instanceData(cfg.id).resolve("instance.json");
        try {
            Files.createDirectories(file.getParent());
            try (Writer w = Files.newBufferedWriter(file)) {
                GSON.toJson(cfg, w);
            }
        } catch (Exception e) {
            log.error("No se pudo guardar instance.json de {}: {}", cfg.id, e.getMessage());
        }
    }

    /** Duplica una instancia (carpeta de juego + content.json) con un id nuevo. */
    public synchronized InstanceConfig duplicate(String id) {
        InstanceConfig src = get(id);
        if (src == null) return null;
        String newId = uniqueId(slug(src.name + " copia"));
        InstanceConfig copy = new InstanceConfig(newId, src.name + " (copia)",
                src.mcVersion, src.loader, src.loaderVersion);
        copy.minMemoryMb = src.minMemoryMb;
        copy.maxMemoryMb = src.maxMemoryMb;
        copy.windowWidth = src.windowWidth;
        copy.windowHeight = src.windowHeight;
        copy.fullscreen = src.fullscreen;
        copy.jvmArgs = src.jvmArgs;
        copy.javaPathOverride = src.javaPathOverride;
        copy.installed = src.installed;
        copy.sourceModpackId = src.sourceModpackId;
        copy.icon = src.icon;
        copy.group = src.group;
        try {
            copyTree(LauncherPaths.instanceGameDir(id), LauncherPaths.instanceGameDir(newId));
            Path srcManifest = LauncherPaths.instanceData(id).resolve("content.json");
            if (Files.exists(srcManifest))
                Files.copy(srcManifest, LauncherPaths.instanceData(newId).resolve("content.json"),
                        StandardCopyOption.REPLACE_EXISTING);
            Path srcIcon = LauncherPaths.instanceIcon(id);
            if (Files.exists(srcIcon))
                Files.copy(srcIcon, LauncherPaths.instanceIcon(newId), StandardCopyOption.REPLACE_EXISTING);
        } catch (Exception e) {
            log.error("No se pudo duplicar {}: {}", id, e.getMessage());
            delete(newId);
            return null;
        }
        save(copy);
        log.info("Instancia {} duplicada como {}.", id, newId);
        return copy;
    }

    private void copyTree(Path src, Path dst) throws IOException {
        Files.createDirectories(dst);
        if (!Files.isDirectory(src)) return;
        try (Stream<Path> walk = Files.walk(src)) {
            for (Path p : walk.toList()) {
                Path target = dst.resolve(src.relativize(p).toString());
                if (Files.isDirectory(p)) {
                    Files.createDirectories(target);
                } else {
                    Files.createDirectories(target.getParent());
                    Files.copy(p, target, StandardCopyOption.REPLACE_EXISTING);
                }
            }
        }
    }

    public synchronized boolean delete(String id) {
        Path game = LauncherPaths.instanceDir(id);
        Path data = LauncherPaths.instancesData().resolve(id);
        if (!Files.isDirectory(game) && !Files.isDirectory(data)) return false;
        boolean ok = deleteTree(game);
        deleteTree(data);
        return ok;
    }

    /** Mueve las carpetas de juego de las instancias al cambiar la ruta global. */
    public synchronized void migrateInstances(Path oldRoot, Path newRoot) {
        if (oldRoot == null || newRoot == null || oldRoot.equals(newRoot)) return;
        if (!Files.isDirectory(oldRoot)) return;
        try {
            Files.createDirectories(newRoot);
        } catch (Exception e) {
            log.error("No se pudo crear la nueva carpeta de instancias {}: {}", newRoot, e.getMessage());
            return;
        }
        try (Stream<Path> dirs = Files.list(oldRoot)) {
            for (Path src : dirs.filter(Files::isDirectory).toList()) {
                Path dst = newRoot.resolve(src.getFileName().toString());
                if (Files.exists(dst)) continue;
                try {
                    Files.move(src, dst, StandardCopyOption.ATOMIC_MOVE);
                } catch (Exception atomicFail) {
                    try {
                        copyTree(src, dst);
                        deleteTree(src);
                    } catch (Exception copyFail) {
                        log.error("No se pudo mover la instancia {}: {}", src.getFileName(), copyFail.getMessage());
                    }
                }
            }
            log.info("Instancias movidas de {} a {}.", oldRoot, newRoot);
        } catch (Exception e) {
            log.error("Error migrando instancias: {}", e.getMessage());
        }
    }

    private boolean deleteTree(Path dir) {
        if (!Files.isDirectory(dir)) return false;
        try (Stream<Path> walk = Files.walk(dir)) {
            walk.sorted(Comparator.reverseOrder()).forEach(p -> {
                try { Files.deleteIfExists(p); } catch (Exception ignored) {}
            });
            return true;
        } catch (Exception e) {
            log.error("No se pudo borrar {}: {}", dir, e.getMessage());
            return false;
        }
    }

    private InstanceConfig readConfig(Path file) {
        if (!Files.exists(file)) return null;
        try (Reader r = Files.newBufferedReader(file)) {
            return GSON.fromJson(r, InstanceConfig.class);
        } catch (Exception e) {
            log.warn("instance.json ilegible {}: {}", file, e.getMessage());
            return null;
        }
    }

    private String uniqueId(String base) {
        String id = base;
        int i = 2;
        while (Files.exists(LauncherPaths.instances().resolve(id))
                || Files.exists(LauncherPaths.instancesData().resolve(id))) {
            id = base + "-" + i++;
        }
        return id;
    }

    private static String slug(String name) {
        String s = name.trim().toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9]+", "-")
                .replaceAll("(^-+|-+$)", "");
        return s.isEmpty() ? "instancia" : s;
    }
}
