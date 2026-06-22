package f24launcher.modpack;

import f24launcher.core.version.VanillaInstaller.Sink;
import f24launcher.util.HttpConnectionPool;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Descarga y aplica un modpack (.mrpack de Modrinth, export de CurseForge o un
 * .zip plano de overlay) dentro del .minecraft de una instancia.
 *
 * El archivo del modpack (índice + overrides) es pequeño; los mods se descargan
 * aparte. La versión de MC y el loader se deducen del propio modpack.
 */
public class ModpackInstaller {

    private static final Logger log = LoggerFactory.getLogger(ModpackInstaller.class);
    private static final String CF = "https://api.curse.tools/v1/cf";

    private final HttpConnectionPool http = HttpConnectionPool.getInstance();
    private final Gson gson = new Gson();

    public record FileEntry(String path, String url) {}

    public record Parsed(String format, String name, String mcVersion,
                         String loader, String loaderVersion,
                         List<FileEntry> files, String overridesPrefix) {}

    /** Descarga el archivo del modpack a un temporal y devuelve su ruta. */
    public Path download(String url, Sink sink) throws Exception {
        sink.update("Descargando modpack", 0, 1);
        byte[] data = http.getBytes(url);
        Path tmp = Files.createTempFile("f24-modpack-", guessExt(url));
        Files.write(tmp, data, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        sink.update("Descargando modpack", 1, 1);
        return tmp;
    }

    public Parsed parse(Path packFile) throws IOException {
        JsonObject mr = readJsonFromZip(packFile, "modrinth.index.json");
        if (mr != null) return parseMrpack(mr);
        JsonObject cf = readJsonFromZip(packFile, "manifest.json");
        if (cf != null) return parseCurseForge(cf);
        return new Parsed("zip", "", "", "", "", new ArrayList<>(), "");
    }

    /** Descarga los archivos del modpack y extrae los overrides al .minecraft. */
    public void apply(Path packFile, Parsed parsed, Path gameDir, Sink sink) throws IOException {
        long total = parsed.files().size();
        long done = 0;
        if (total > 0) {
            sink.update("Contenido", 0, total);
            for (FileEntry fe : parsed.files()) {
                Path dest = gameDir.resolve(fe.path()).normalize();
                if (!dest.startsWith(gameDir)) continue;
                try {
                    Files.createDirectories(dest.getParent());
                    Files.write(dest, http.getBytes(fe.url()),
                            StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
                } catch (Exception e) {
                    log.warn("No se pudo descargar {}: {}", fe.path(), e.getMessage());
                }
                sink.update("Contenido", ++done, total);
            }
        }
        switch (parsed.format()) {
            case "mrpack" -> {
                extract(packFile, gameDir, "overrides", sink);
                extract(packFile, gameDir, "client-overrides", sink);
            }
            case "curseforge" -> extract(packFile, gameDir, parsed.overridesPrefix(), sink);
            default -> extract(packFile, gameDir, "", sink);
        }
    }

    // ── Parsers ───────────────────────────────────────────────────────

    private Parsed parseMrpack(JsonObject index) {
        String name = str(index, "name");
        JsonObject deps = index.has("dependencies") && index.get("dependencies").isJsonObject()
                ? index.getAsJsonObject("dependencies") : new JsonObject();
        String mc = str(deps, "minecraft");
        String loader = "", lv = "";
        if (!str(deps, "fabric-loader").isBlank()) { loader = "fabric"; lv = str(deps, "fabric-loader"); }
        else if (!str(deps, "quilt-loader").isBlank()) { loader = "quilt"; lv = str(deps, "quilt-loader"); }
        else if (!str(deps, "neoforge").isBlank()) { loader = "neoforge"; lv = str(deps, "neoforge"); }
        else if (!str(deps, "forge").isBlank()) { loader = "forge"; lv = str(deps, "forge"); }

        List<FileEntry> files = new ArrayList<>();
        if (index.has("files")) for (JsonElement el : index.getAsJsonArray("files")) {
            JsonObject f = el.getAsJsonObject();
            if (f.has("env") && f.get("env").isJsonObject()
                    && "unsupported".equalsIgnoreCase(str(f.getAsJsonObject("env"), "client"))) continue;
            String path = str(f, "path");
            JsonArray dl = f.has("downloads") ? f.getAsJsonArray("downloads") : null;
            if (path.isBlank() || dl == null || dl.isEmpty()) continue;
            files.add(new FileEntry(path, dl.get(0).getAsString()));
        }
        return new Parsed("mrpack", name, mc, loader, lv, files, "overrides");
    }

    private Parsed parseCurseForge(JsonObject manifest) {
        String name = str(manifest, "name");
        String mc = "", loader = "", lv = "";
        if (manifest.has("minecraft") && manifest.get("minecraft").isJsonObject()) {
            JsonObject m = manifest.getAsJsonObject("minecraft");
            mc = str(m, "version");
            if (m.has("modLoaders") && m.get("modLoaders").isJsonArray()) {
                JsonArray ls = m.getAsJsonArray("modLoaders");
                for (JsonElement le : ls) {
                    JsonObject lo = le.getAsJsonObject();
                    boolean primary = !lo.has("primary") || lo.get("primary").getAsBoolean();
                    if (primary || ls.size() == 1) {
                        String idv = str(lo, "id"); // p.ej. "forge-47.2.0" o "neoforge-21.1.5"
                        int dash = idv.indexOf('-');
                        if (dash > 0) { loader = idv.substring(0, dash); lv = idv.substring(dash + 1); }
                        break;
                    }
                }
            }
        }
        List<FileEntry> files = new ArrayList<>();
        if (manifest.has("files")) for (JsonElement el : manifest.getAsJsonArray("files")) {
            JsonObject f = el.getAsJsonObject();
            if (!f.has("projectID") || !f.has("fileID")) continue;
            String pid = f.get("projectID").getAsString();
            String fid = f.get("fileID").getAsString();
            try {
                JsonObject resp = gson.fromJson(http.get(CF + "/mods/" + pid + "/files/" + fid), JsonObject.class);
                JsonObject d = resp.getAsJsonObject("data");
                String url = cfDownloadUrl(d);
                String fn = str(d, "fileName");
                if (url != null && !fn.isBlank()) files.add(new FileEntry("mods/" + fn, url));
            } catch (Exception e) {
                log.warn("No se pudo resolver archivo CF {}/{}: {}", pid, fid, e.getMessage());
            }
        }
        String overrides = !str(manifest, "overrides").isBlank() ? str(manifest, "overrides") : "overrides";
        return new Parsed("curseforge", name, mc, loader, lv, files, overrides);
    }

    // ── Extracción de overrides ───────────────────────────────────────

    private void extract(Path packFile, Path gameDir, String prefix, Sink sink) throws IOException {
        boolean root = prefix == null || prefix.isBlank();
        String pfx = root ? "" : (prefix.endsWith("/") ? prefix : prefix + "/");
        try (ZipFile zip = new ZipFile(packFile.toFile(), StandardCharsets.UTF_8)) {
            List<? extends ZipEntry> entries = zip.stream()
                    .filter(e -> !e.isDirectory())
                    .filter(e -> root ? isOverlay(e.getName()) : e.getName().startsWith(pfx))
                    .toList();
            if (entries.isEmpty()) return;
            long total = entries.size();
            long done = 0;
            sink.update("Extracción", 0, total);
            for (ZipEntry e : entries) {
                String rel = root ? e.getName() : e.getName().substring(pfx.length());
                if (rel.isBlank()) continue;
                Path dest = gameDir.resolve(rel).normalize();
                if (!dest.startsWith(gameDir)) continue; // protección zip-slip
                Files.createDirectories(dest.getParent());
                try (InputStream is = zip.getInputStream(e)) {
                    Files.write(dest, is.readAllBytes(),
                            StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
                }
                sink.update("Extracción", ++done, total);
            }
        }
    }

    private boolean isOverlay(String name) {
        return !name.equals("modrinth.index.json") && !name.equals("manifest.json")
                && !name.equals("modlist.html");
    }

    // ── Helpers ───────────────────────────────────────────────────────

    private JsonObject readJsonFromZip(Path zipPath, String entryName) throws IOException {
        try (ZipFile zip = new ZipFile(zipPath.toFile(), StandardCharsets.UTF_8)) {
            ZipEntry entry = zip.getEntry(entryName);
            if (entry == null) return null;
            try (InputStream is = zip.getInputStream(entry)) {
                return gson.fromJson(new String(is.readAllBytes(), StandardCharsets.UTF_8), JsonObject.class);
            }
        } catch (Exception e) {
            return null;
        }
    }

    private String cfDownloadUrl(JsonObject file) {
        if (file.has("downloadUrl") && !file.get("downloadUrl").isJsonNull())
            return file.get("downloadUrl").getAsString();
        if (file.has("id") && file.has("fileName")) {
            long fid = file.get("id").getAsLong();
            return String.format("https://mediafilez.forgecdn.net/files/%d/%d/%s",
                    fid / 1000, fid % 1000, str(file, "fileName"));
        }
        return null;
    }

    private static String guessExt(String url) {
        return url.toLowerCase(Locale.ROOT).endsWith(".mrpack") ? ".mrpack" : ".zip";
    }

    private static String str(JsonObject o, String key) {
        return o != null && o.has(key) && !o.get(key).isJsonNull() ? o.get(key).getAsString() : "";
    }
}
