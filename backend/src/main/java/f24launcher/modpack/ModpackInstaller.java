package f24launcher.modpack;

import f24launcher.content.ObjectStore;
import f24launcher.core.version.VanillaInstaller.Sink;
import f24launcher.util.DownloadManager;
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
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
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
    private final DownloadManager dm = DownloadManager.getInstance();
    private final Gson gson = new Gson();

    /** Un archivo del modpack: ruta relativa, URL y (si se conocen) tamaño/hashes. */
    public record FileEntry(String path, String url, long size, String sha1, String sha512) {
        public FileEntry(String path, String url) { this(path, url, 0, null, null); }
    }

    public record Parsed(String format, String name, String mcVersion,
                         String loader, String loaderVersion,
                         List<FileEntry> files, String overridesPrefix) {}

    /** Extras propios de un .f24pack (f24launcher.json). Valores 0/"" = ausentes. */
    public record F24Meta(String icon, int minMemoryMb, int maxMemoryMb,
                          int windowWidth, int windowHeight, String jvmArgs, String sourceModpackId) {}

    /** Lee f24launcher.json del pack si existe; null si no está. */
    public F24Meta readF24Meta(Path packFile) {
        try {
            JsonObject o = readJsonFromZip(packFile, "f24launcher.json");
            if (o == null) return null;
            return new F24Meta(str(o, "icon"), intOr(o, "minMemoryMb"), intOr(o, "maxMemoryMb"),
                    intOr(o, "windowWidth"), intOr(o, "windowHeight"), str(o, "jvmArgs"), str(o, "sourceModpackId"));
        } catch (Exception e) {
            return null;
        }
    }

    /** Lee los bytes de una entrada del pack (p. ej. el icono); null si no existe. */
    public byte[] readEntry(Path packFile, String name) {
        try (ZipFile zip = new ZipFile(packFile.toFile(), StandardCharsets.UTF_8)) {
            ZipEntry e = zip.getEntry(name);
            if (e == null) return null;
            try (InputStream is = zip.getInputStream(e)) {
                return is.readAllBytes();
            }
        } catch (Exception e) {
            return null;
        }
    }

    /** Descarga el archivo del modpack a un temporal (por streaming) y devuelve su ruta. */
    public Path download(String url, Sink sink) throws Exception {
        sink.update("Descargando modpack", 0, 1);
        Path tmp = Files.createTempFile("f24-modpack-", guessExt(url));
        Files.deleteIfExists(tmp); // el DownloadManager escribe a su .part y mueve aquí
        DownloadManager.Result r = dm.download(new DownloadManager.Task(url, tmp, 0, null, null), false);
        if (!r.ok()) throw new IOException("No se pudo descargar el modpack: " + url, r.error());
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

    /** Descarga los archivos del modpack (en paralelo) y extrae los overrides al .minecraft. */
    public void apply(Path packFile, Parsed parsed, Path gameDir, Sink sink) throws IOException {
        if (!parsed.files().isEmpty()) {
            List<DownloadManager.Task> tasks = new ArrayList<>();
            for (FileEntry fe : parsed.files()) {
                Path dest = gameDir.resolve(fe.path()).normalize();
                if (!dest.startsWith(gameDir)) continue; // protección zip-slip
                // Dedup: si el contenido ya está en el store, enlaza y evita la descarga.
                if (ObjectStore.tryLink(dest, fe.sha1())) continue;
                tasks.add(new DownloadManager.Task(fe.url(), dest, fe.size(), fe.sha1(), fe.sha512()));
            }
            List<DownloadManager.Result> results =
                    dm.downloadAll(tasks, (d, t) -> sink.update("Contenido", d, t), false, ObjectStore::linkWriteFile);
            for (DownloadManager.Result r : results) {
                if (r.status() == DownloadManager.Status.FAILED)
                    log.warn("No se pudo descargar {}: {}", r.task().dest().getFileName(),
                            r.error() != null ? r.error().getMessage() : "");
            }
            if (Thread.currentThread().isInterrupted())
                throw new IOException("Instalación de modpack cancelada");
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

    // ── Actualización diferencial (Fase 4) ────────────────────────────

    /** Un override del pack: ruta destino (rel al .minecraft), su nombre en el zip, hash y tamaño. */
    public record OverrideEntry(String path, String zipName, String sha1, long size) {}

    /** Contenido completo de un pack: archivos descargables ({@code files[]}) + overrides, con hashes. */
    public record PackContents(List<FileEntry> files, List<OverrideEntry> overrides) {}

    /** Plan de actualización: qué descargar (mods), qué extraer (overrides) y qué borrar. */
    public record Plan(List<FileEntry> downloads, List<OverrideEntry> extracts, List<String> removals) {
        public boolean isEmpty() { return downloads.isEmpty() && extracts.isEmpty() && removals.isEmpty(); }
        public int total() { return downloads.size() + extracts.size() + removals.size(); }
    }

    /**
     * Escanea el pack y calcula su contenido: los {@code files[]} (ya traen ruta/url/hash)
     * y todos los overrides, hasheados en streaming (memoria constante). Es la base para
     * el diff de una actualización.
     */
    public PackContents scan(Path packFile, Parsed parsed) throws IOException {
        List<OverrideEntry> overrides = new ArrayList<>();
        try (ZipFile zip = new ZipFile(packFile.toFile(), StandardCharsets.UTF_8)) {
            var entries = zip.entries();
            while (entries.hasMoreElements()) {
                ZipEntry e = entries.nextElement();
                if (e.isDirectory()) continue;
                String rel = overrideRel(parsed, e.getName());
                if (rel == null || rel.isBlank()) continue;
                try (InputStream is = zip.getInputStream(e)) {
                    MessageDigest md = MessageDigest.getInstance("SHA-1");
                    byte[] buf = new byte[1 << 16];
                    long size = 0;
                    int n;
                    while ((n = is.read(buf)) > 0) { md.update(buf, 0, n); size += n; }
                    overrides.add(new OverrideEntry(rel, e.getName(), hex(md.digest()), size));
                } catch (Exception ex) {
                    log.warn("No se pudo hashear el override {}: {}", e.getName(), ex.getMessage());
                }
            }
        }
        return new PackContents(parsed.files(), overrides);
    }

    /** Construye el manifiesto del modpack instalado a partir de un {@link PackContents}. */
    public ModpackManifest manifestFrom(PackContents pc, String modpackId, String version,
                                        String variant, String format) {
        ModpackManifest m = new ModpackManifest();
        m.modpackId = modpackId == null ? "" : modpackId;
        m.version = version == null ? "" : version;
        m.variant = variant == null ? "" : variant;
        m.format = format == null ? "" : format;
        Set<String> seen = new HashSet<>();
        for (FileEntry fe : pc.files()) {
            if (fe.path() == null || fe.path().isBlank()) continue;
            if (seen.add(fe.path()))
                m.files.add(new ModpackManifest.Entry(fe.path(), fe.sha1(), fe.size(), ModpackManifest.Origin.DOWNLOADED));
        }
        for (OverrideEntry oe : pc.overrides()) {
            if (seen.add(oe.path()))
                m.files.add(new ModpackManifest.Entry(oe.path(), oe.sha1(), oe.size(), ModpackManifest.Origin.OVERRIDE));
        }
        return m;
    }

    /** Aplica un plan de actualización: descarga lo cambiado, extrae los overrides cambiados y borra lo retirado. */
    public void applyPlan(Path packFile, Path gameDir, Plan plan, Sink sink) throws IOException {
        // 1) Mods nuevos/cambiados (streaming a disco con dedup).
        if (!plan.downloads().isEmpty()) {
            List<DownloadManager.Task> tasks = new ArrayList<>();
            for (FileEntry fe : plan.downloads()) {
                Path dest = gameDir.resolve(fe.path()).normalize();
                if (!dest.startsWith(gameDir)) continue; // protección zip-slip
                tasks.add(new DownloadManager.Task(fe.url(), dest, fe.size(), fe.sha1(), fe.sha512()));
            }
            List<DownloadManager.Result> results =
                    dm.downloadAll(tasks, (d, t) -> sink.update("Actualizando contenido", d, t), false, ObjectStore::linkWriteFile);
            for (DownloadManager.Result r : results)
                if (r.status() == DownloadManager.Status.FAILED)
                    log.warn("No se pudo descargar {}: {}", r.task().dest().getFileName(),
                            r.error() != null ? r.error().getMessage() : "");
        }
        // 2) Overrides nuevos/cambiados (extracción selectiva por streaming).
        if (!plan.extracts().isEmpty()) {
            try (ZipFile zip = new ZipFile(packFile.toFile(), StandardCharsets.UTF_8)) {
                long total = plan.extracts().size();
                long done = 0;
                sink.update("Actualizando configuración", 0, total);
                for (OverrideEntry oe : plan.extracts()) {
                    ZipEntry e = zip.getEntry(oe.zipName());
                    if (e == null) continue;
                    Path dest = gameDir.resolve(oe.path()).normalize();
                    if (!dest.startsWith(gameDir)) continue; // protección zip-slip
                    Files.createDirectories(dest.getParent());
                    try (InputStream is = zip.getInputStream(e)) {
                        Files.copy(is, dest, StandardCopyOption.REPLACE_EXISTING);
                    }
                    sink.update("Actualizando configuración", ++done, total);
                }
            }
        }
        // 3) Archivos del modpack retirados en la nueva versión (incluye su variante .disabled).
        for (String rel : plan.removals()) {
            Path dest = gameDir.resolve(rel).normalize();
            if (!dest.startsWith(gameDir)) continue;
            try {
                Files.deleteIfExists(dest);
                Files.deleteIfExists(dest.resolveSibling(dest.getFileName() + ".disabled"));
            } catch (Exception ignored) {}
        }
    }

    /** Ruta destino (rel al .minecraft) de una entrada del zip si es un override; null si no lo es. */
    private String overrideRel(Parsed parsed, String name) {
        switch (parsed.format()) {
            case "mrpack" -> {
                String r = stripPrefix(name, "overrides/");
                return r != null ? r : stripPrefix(name, "client-overrides/");
            }
            case "curseforge" -> {
                String pfx = (parsed.overridesPrefix() == null || parsed.overridesPrefix().isBlank())
                        ? "overrides" : parsed.overridesPrefix();
                return stripPrefix(name, pfx.endsWith("/") ? pfx : pfx + "/");
            }
            default -> {
                return isOverlay(name) ? name : null;
            }
        }
    }

    private static String stripPrefix(String name, String pfx) {
        return name.startsWith(pfx) && name.length() > pfx.length() ? name.substring(pfx.length()) : null;
    }

    private static String hex(byte[] digest) {
        StringBuilder sb = new StringBuilder(digest.length * 2);
        for (byte b : digest) sb.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
        return sb.toString();
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
            long size = longOr(f, "fileSize");
            String sha1 = "", sha512 = "";
            if (f.has("hashes") && f.get("hashes").isJsonObject()) {
                JsonObject h = f.getAsJsonObject("hashes");
                sha1 = str(h, "sha1");
                sha512 = str(h, "sha512");
            }
            files.add(new FileEntry(path, dl.get(0).getAsString(), size, sha1, sha512));
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
        // Resolver cada archivo del manifiesto (una llamada HTTP por fichero) en paralelo.
        List<String[]> refs = new ArrayList<>();
        if (manifest.has("files")) for (JsonElement el : manifest.getAsJsonArray("files")) {
            JsonObject f = el.getAsJsonObject();
            if (!f.has("projectID") || !f.has("fileID")) continue;
            refs.add(new String[]{f.get("projectID").getAsString(), f.get("fileID").getAsString()});
        }
        List<FileEntry> files = new ArrayList<>();
        try (ExecutorService exec = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<FileEntry>> futures = new ArrayList<>();
            for (String[] ref : refs) futures.add(exec.submit(() -> resolveCfFile(ref[0], ref[1])));
            for (Future<FileEntry> fu : futures) {
                try {
                    FileEntry fe = fu.get();
                    if (fe != null) files.add(fe);
                } catch (Exception e) {
                    log.warn("No se pudo resolver un archivo de CurseForge: {}", e.getMessage());
                }
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
                // Copia por streaming (sin cargar la entrada entera en memoria).
                try (InputStream is = zip.getInputStream(e)) {
                    Files.copy(is, dest, StandardCopyOption.REPLACE_EXISTING);
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

    /** Resuelve un archivo de CurseForge (URL + nombre + tamaño + sha1); null si no se puede. */
    private FileEntry resolveCfFile(String pid, String fid) {
        try {
            JsonObject resp = gson.fromJson(http.get(CF + "/mods/" + pid + "/files/" + fid), JsonObject.class);
            JsonObject d = resp.getAsJsonObject("data");
            String url = cfDownloadUrl(d);
            String fn = str(d, "fileName");
            if (url == null || fn.isBlank()) return null;
            return new FileEntry("mods/" + fn, url, longOr(d, "fileLength"), cfSha1(d), "");
        } catch (Exception e) {
            log.warn("No se pudo resolver archivo CF {}/{}: {}", pid, fid, e.getMessage());
            return null;
        }
    }

    /** sha1 de un archivo de CurseForge (hashes[].algo == 1); "" si no está. */
    private String cfSha1(JsonObject file) {
        if (file.has("hashes") && file.get("hashes").isJsonArray()) {
            for (JsonElement he : file.getAsJsonArray("hashes")) {
                JsonObject h = he.getAsJsonObject();
                if (h.has("algo") && h.get("algo").getAsInt() == 1 && h.has("value"))
                    return h.get("value").getAsString();
            }
        }
        return "";
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

    private static int intOr(JsonObject o, String key) {
        try {
            return o != null && o.has(key) && !o.get(key).isJsonNull() ? o.get(key).getAsInt() : 0;
        } catch (Exception e) {
            return 0;
        }
    }

    private static long longOr(JsonObject o, String key) {
        try {
            return o != null && o.has(key) && !o.get(key).isJsonNull() ? o.get(key).getAsLong() : 0;
        } catch (Exception e) {
            return 0;
        }
    }
}
