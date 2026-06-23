package f24launcher.modpack;

import f24launcher.content.ContentManifest;
import f24launcher.content.ContentType;
import f24launcher.core.LauncherPaths;
import f24launcher.instance.InstanceConfig;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Exporta una instancia a un archivo .f24pack (superconjunto de .mrpack) o a un
 * .mrpack estándar. El contenido instalado con origen conocido (URL + hashes) va
 * como {@code files[]} (se descarga al importar); el resto (configs, mods sin
 * origen, mundos…) va en {@code overrides/}.
 */
public class ModpackExporter {

    private static final Logger log = LoggerFactory.getLogger(ModpackExporter.class);
    private final Gson gson = new Gson();

    /** Opciones editables de la ventana de exportación (todas con valor por defecto del estado actual). */
    public static class Options {
        public String outputPath;
        public String name;
        public Integer minMemoryMb, maxMemoryMb, windowWidth, windowHeight;
        public String jvmArgs;
        /** Rutas relativas (carpetas o archivos) de la instancia a incluir en overrides. */
        public List<String> includePaths;
        public boolean includeIcon = true;
        public String format = "f24pack"; // "f24pack" | "mrpack"
    }

    public void export(InstanceConfig cfg, Options opt) throws IOException {
        Path game = LauncherPaths.instanceGameDir(cfg.id);
        ContentManifest manifest = ContentManifest.load(cfg.id);
        boolean f24 = !"mrpack".equalsIgnoreCase(opt.format);

        // 1) files[] desde el manifiesto (solo habilitados, con URL + hashes y aún presentes).
        JsonArray files = new JsonArray();
        Set<String> covered = new HashSet<>();
        for (ContentManifest.Entry e : manifest.items) {
            if (e.fileName == null || e.fileName.endsWith(".disabled")) continue;
            boolean hasDownload = notBlank(e.downloadUrl) && notBlank(e.sha1) && notBlank(e.sha512);
            if (!hasDownload) continue;
            String folder = ContentType.from(e.type).folder;
            if (!Files.exists(game.resolve(folder).resolve(e.fileName))) continue;
            String rel = folder + "/" + e.fileName;
            JsonObject f = new JsonObject();
            f.addProperty("path", rel);
            JsonObject hashes = new JsonObject();
            hashes.addProperty("sha1", e.sha1);
            hashes.addProperty("sha512", e.sha512);
            f.add("hashes", hashes);
            JsonArray dl = new JsonArray();
            dl.add(e.downloadUrl);
            f.add("downloads", dl);
            f.addProperty("fileSize", e.fileSize);
            files.add(f);
            covered.add(rel);
        }

        // 2) índice estilo Modrinth.
        JsonObject index = new JsonObject();
        index.addProperty("formatVersion", 1);
        index.addProperty("game", "minecraft");
        index.addProperty("versionId", String.valueOf(System.currentTimeMillis()));
        index.addProperty("name", notBlank(opt.name) ? opt.name : cfg.name);
        index.addProperty("summary", "");
        index.add("files", files);
        JsonObject deps = new JsonObject();
        deps.addProperty("minecraft", cfg.mcVersion);
        String depKey = loaderDepKey(cfg.loader);
        if (depKey != null && notBlank(cfg.loaderVersion)) deps.addProperty(depKey, cfg.loaderVersion);
        index.add("dependencies", deps);

        // 3) escribir el ZIP.
        Path out = Paths.get(opt.outputPath);
        if (out.getParent() != null) Files.createDirectories(out.getParent());
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(out))) {
            putEntry(zip, "modrinth.index.json", gson.toJson(index).getBytes(StandardCharsets.UTF_8));

            if (f24) {
                JsonObject m = new JsonObject();
                m.addProperty("formatVersion", 1);
                boolean hasIcon = opt.includeIcon && Files.exists(LauncherPaths.instanceIcon(cfg.id));
                if (hasIcon) m.addProperty("icon", "icon.png");
                m.addProperty("minMemoryMb", opt.minMemoryMb != null ? opt.minMemoryMb : cfg.minMemoryMb);
                m.addProperty("maxMemoryMb", opt.maxMemoryMb != null ? opt.maxMemoryMb : cfg.maxMemoryMb);
                m.addProperty("windowWidth", opt.windowWidth != null ? opt.windowWidth : cfg.windowWidth);
                m.addProperty("windowHeight", opt.windowHeight != null ? opt.windowHeight : cfg.windowHeight);
                m.addProperty("jvmArgs", opt.jvmArgs != null ? opt.jvmArgs : cfg.jvmArgs);
                if (notBlank(cfg.sourceModpackId)) m.addProperty("sourceModpackId", cfg.sourceModpackId);
                putEntry(zip, "f24launcher.json", gson.toJson(m).getBytes(StandardCharsets.UTF_8));
                if (hasIcon) putEntry(zip, "icon.png", Files.readAllBytes(LauncherPaths.instanceIcon(cfg.id)));
            }

            // 4) overrides: contenido sin origen conocido + carpetas seleccionadas.
            for (ContentType t : ContentType.values()) {
                Path dir = game.resolve(t.folder);
                if (!Files.isDirectory(dir)) continue;
                try (Stream<Path> s = Files.list(dir)) {
                    for (Path p : s.filter(Files::isRegularFile).toList()) {
                        String fn = p.getFileName().toString();
                        if (fn.endsWith(".disabled")) continue;
                        String rel = t.folder + "/" + fn;
                        if (covered.contains(rel)) continue;
                        putEntry(zip, "overrides/" + rel, Files.readAllBytes(p));
                    }
                }
            }
            // 4b) rutas extra elegidas por el usuario (carpetas o archivos sueltos).
            if (opt.includePaths != null) {
                Set<String> contentRoots = new HashSet<>();
                for (ContentType t : ContentType.values()) contentRoots.add(t.folder);
                for (String raw : opt.includePaths) {
                    if (raw == null || raw.isBlank()) continue;
                    String rel = raw.replace('\\', '/').replaceAll("^/+", "");
                    if (rel.isEmpty()) continue;
                    if (contentRoots.contains(rel.split("/")[0])) continue; // el contenido se maneja aparte
                    Path p = game.resolve(rel).normalize();
                    if (!p.startsWith(game)) continue; // protección
                    if (Files.isDirectory(p)) addTree(zip, p, "overrides/" + rel);
                    else if (Files.isRegularFile(p)) putEntry(zip, "overrides/" + rel, Files.readAllBytes(p));
                }
            }
        }
        log.info("Instancia {} exportada a {}", cfg.id, out);
    }

    private void addTree(ZipOutputStream zip, Path srcDir, String prefix) throws IOException {
        if (!Files.isDirectory(srcDir)) return;
        try (Stream<Path> walk = Files.walk(srcDir)) {
            for (Path p : walk.filter(Files::isRegularFile).toList()) {
                String rel = srcDir.relativize(p).toString().replace('\\', '/');
                putEntry(zip, prefix + "/" + rel, Files.readAllBytes(p));
            }
        }
    }

    private void putEntry(ZipOutputStream zip, String name, byte[] data) throws IOException {
        zip.putNextEntry(new ZipEntry(name));
        zip.write(data);
        zip.closeEntry();
    }

    private static String loaderDepKey(String loader) {
        if (loader == null) return null;
        return switch (loader.toLowerCase()) {
            case "fabric" -> "fabric-loader";
            case "quilt" -> "quilt-loader";
            case "forge" -> "forge";
            case "neoforge" -> "neoforge";
            default -> null;
        };
    }

    private static boolean notBlank(String s) {
        return s != null && !s.isBlank();
    }
}
