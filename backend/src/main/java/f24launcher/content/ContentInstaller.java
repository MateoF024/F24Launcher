package f24launcher.content;

import f24launcher.content.ContentModels.*;
import f24launcher.core.LauncherPaths;
import f24launcher.instance.InstanceConfig;
import f24launcher.util.HttpConnectionPool;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.*;
import java.util.*;
import java.util.stream.Stream;

/**
 * Instala, activa/desactiva y elimina contenido en una instancia, manteniendo
 * sincronizados los archivos de .minecraft con {@link ContentManifest}.
 */
public class ContentInstaller {

    private static final Logger log = LoggerFactory.getLogger(ContentInstaller.class);

    private final ContentService service;

    public ContentInstaller(ContentService service) { this.service = service; }

    /** Instala un proyecto (versión concreta o la mejor compatible) y sus dependencias requeridas. */
    public InstalledItem install(InstanceConfig cfg, String source, ContentType type,
                                 String projectId, String versionId, boolean ignoreFilters) throws Exception {
        ContentManifest manifest = ContentManifest.load(cfg.id);
        InstalledItem item = installInto(cfg, source, type, projectId, versionId, ignoreFilters, manifest, new HashSet<>());
        manifest.save(cfg.id);
        return item;
    }

    private InstalledItem installInto(InstanceConfig cfg, String source, ContentType type, String projectId,
                                      String versionId, boolean ignoreFilters, ContentManifest manifest,
                                      Set<String> visited) throws Exception {
        String key = source + ":" + projectId + ":" + type.id;
        if (!visited.add(key)) return null;

        Version version = resolve(source, type, projectId, versionId, cfg, ignoreFilters);
        if (version == null) throw new IllegalStateException("Sin versión compatible para " + projectId);

        Path dir = folder(cfg, type);
        Files.createDirectories(dir);
        Path dest = dir.resolve(sanitize(version.fileName()));
        byte[] data = HttpConnectionPool.getInstance().getBytes(version.downloadUrl());
        Files.write(dest, data, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);

        ProjectDetail meta = safeDetail(source, type, projectId);
        ContentManifest.Entry e = manifest.find(source, projectId, type.id);
        if (e == null) { e = new ContentManifest.Entry(); manifest.items.add(e); }
        else deleteIfExists(dir, e.fileName); // reemplaza archivo anterior (cambio de versión)
        e.fileName = dest.getFileName().toString();
        e.type = type.id;
        e.source = source;
        e.projectId = projectId;
        e.slug = meta != null ? meta.slug() : projectId;
        e.name = meta != null ? meta.name() : version.fileName();
        e.iconUrl = meta != null ? meta.iconUrl() : "";
        e.author = meta != null ? meta.author() : "";
        e.versionId = version.id();
        e.versionName = version.versionNumber();
        e.categories = meta != null ? meta.categories() : new ArrayList<>();
        e.clientSide = meta != null ? meta.clientSide() : "";
        e.serverSide = meta != null ? meta.serverSide() : "";

        // Dependencias requeridas (se tratan como mods).
        for (Dependency dep : version.dependencies()) {
            if (!"required".equalsIgnoreCase(dep.type())) continue;
            if (manifest.find(source, dep.projectId(), ContentType.MODS.id) != null) continue;
            try {
                installInto(cfg, source, ContentType.MODS, dep.projectId(), null, ignoreFilters, manifest, visited);
            } catch (Exception ex) {
                log.warn("No se pudo instalar dependencia {}: {}", dep.projectId(), ex.getMessage());
            }
        }

        log.info("Instalado {} ({}) en {}", e.name, version.versionNumber(), cfg.id);
        return toItem(e);
    }

    private Version resolve(String source, ContentType type, String projectId, String versionId,
                            InstanceConfig cfg, boolean ignoreFilters) throws Exception {
        List<Version> versions = service.versions(source, type, projectId, cfg.mcVersion, cfg.loader, ignoreFilters);
        if (versions.isEmpty() && !ignoreFilters)
            versions = service.versions(source, type, projectId, cfg.mcVersion, cfg.loader, true);
        if (versionId != null && !versionId.isBlank())
            for (Version v : versions) if (v.id().equals(versionId)) return v;
        return versions.isEmpty() ? null : versions.get(0);
    }

    /** Activa o desactiva un archivo renombrándolo con el sufijo .disabled. */
    public boolean toggle(InstanceConfig cfg, String type, String fileName) throws Exception {
        Path dir = folder(cfg, ContentType.from(type));
        Path current = dir.resolve(fileName);
        if (!Files.exists(current)) return false;
        boolean enabled = !fileName.endsWith(".disabled");
        String target = enabled ? fileName + ".disabled" : fileName.substring(0, fileName.length() - 9);
        Path moved = dir.resolve(target);
        Files.move(current, moved, StandardCopyOption.REPLACE_EXISTING);
        ContentManifest manifest = ContentManifest.load(cfg.id);
        ContentManifest.Entry e = manifest.findByFile(fileName);
        if (e != null) { e.fileName = target; manifest.save(cfg.id); }
        return true;
    }

    public boolean remove(InstanceConfig cfg, String type, String fileName) throws Exception {
        Path dir = folder(cfg, ContentType.from(type));
        boolean removed = deleteIfExists(dir, fileName);
        ContentManifest manifest = ContentManifest.load(cfg.id);
        ContentManifest.Entry e = manifest.findByFile(fileName);
        if (e != null) { manifest.remove(e); manifest.save(cfg.id); }
        return removed;
    }

    /** Lista el contenido instalado, fusionando el manifiesto con un escaneo de carpetas. */
    public List<InstalledItem> list(InstanceConfig cfg) {
        ContentManifest manifest = ContentManifest.load(cfg.id);
        Map<String, InstalledItem> byBase = new LinkedHashMap<>();
        for (ContentManifest.Entry e : manifest.items) byBase.put(baseKey(e.type, e.fileName), toItem(e));

        for (ContentType type : ContentType.values()) {
            Path dir = folder(cfg, type);
            if (!Files.isDirectory(dir)) continue;
            try (Stream<Path> files = Files.list(dir)) {
                files.filter(Files::isRegularFile).forEach(p -> {
                    String fn = p.getFileName().toString();
                    if (!isContentFile(fn)) return;
                    String k = baseKey(type.id, fn);
                    if (byBase.containsKey(k)) return;
                    boolean enabled = !fn.endsWith(".disabled");
                    byBase.put(k, new InstalledItem(fn, type.id, enabled, "", "", "", fn, "", "", "", "",
                            List.of(), "", ""));
                });
            } catch (Exception ignored) {}
        }
        List<InstalledItem> out = new ArrayList<>(byBase.values());
        out.sort(Comparator.comparing(InstalledItem::type).thenComparing(i -> i.name().toLowerCase()));
        return out;
    }

    /**
     * Intenta identificar los mods/archivos añadidos manualmente (sin entrada en
     * el manifiesto): primero por hash en Modrinth y, si falla, por nombre en
     * CurseForge. Los que se reconocen se persisten en el manifiesto.
     */
    public List<InstalledItem> identify(InstanceConfig cfg) {
        ContentManifest manifest = ContentManifest.load(cfg.id);
        boolean dirty = false;
        for (ContentType type : ContentType.values()) {
            Path dir = folder(cfg, type);
            if (!Files.isDirectory(dir)) continue;
            try (Stream<Path> files = Files.list(dir)) {
                for (Path p : files.filter(Files::isRegularFile).toList()) {
                    String fn = p.getFileName().toString();
                    if (!isContentFile(fn)) continue;
                    if (manifest.findByFile(fn) != null) continue;
                    ContentManifest.Entry e = resolveMeta(cfg, type, p, fn);
                    if (e != null) { manifest.items.add(e); dirty = true; }
                }
            } catch (Exception ignored) {}
        }
        if (dirty) manifest.save(cfg.id);
        return list(cfg);
    }

    private ContentManifest.Entry resolveMeta(InstanceConfig cfg, ContentType type, Path file, String fileName) {
        try {
            String sha1 = sha1(file);
            ContentService.ModrinthHit hit = service.modrinthByHash(sha1);
            if (hit != null) {
                ProjectDetail d = safeDetail("modrinth", type, hit.projectId());
                return entryFrom(fileName, type, "modrinth", hit.projectId(),
                        hit.versionId(), hit.versionNumber(), d);
            }
        } catch (Exception e) {
            log.debug("hash {}: {}", fileName, e.getMessage());
        }
        try {
            String term = searchTerm(fileName);
            if (term.length() >= 3) {
                var res = service.search("curseforge", type, cfg.mcVersion, cfg.loader,
                        term, null, null, "relevance", 0, true);
                if (!res.hits().isEmpty()) {
                    Project p = res.hits().get(0);
                    if (looseMatch(term, p.name(), p.slug())) {
                        ProjectDetail d = safeDetail("curseforge", type, p.id());
                        return entryFrom(fileName, type, "curseforge", p.id(), "", "", d);
                    }
                }
            }
        } catch (Exception e) {
            log.debug("cf search {}: {}", fileName, e.getMessage());
        }
        return null;
    }

    private ContentManifest.Entry entryFrom(String fileName, ContentType type, String source,
                                            String projectId, String versionId, String versionName,
                                            ProjectDetail d) {
        ContentManifest.Entry e = new ContentManifest.Entry();
        e.fileName = fileName;
        e.type = type.id;
        e.source = source;
        e.projectId = projectId;
        e.slug = d != null ? d.slug() : projectId;
        e.name = d != null ? d.name() : fileName;
        e.iconUrl = d != null ? d.iconUrl() : "";
        e.author = d != null ? d.author() : "";
        e.versionId = versionId;
        e.versionName = !versionName.isBlank() ? versionName : fileName;
        e.categories = d != null ? d.categories() : new ArrayList<>();
        e.clientSide = d != null ? d.clientSide() : "";
        e.serverSide = d != null ? d.serverSide() : "";
        return e;
    }

    /** Deriva un término de búsqueda del nombre de archivo (quita versión, loader, extensión). */
    private String searchTerm(String fileName) {
        String f = fileName.endsWith(".disabled") ? fileName.substring(0, fileName.length() - 9) : fileName;
        f = f.replaceAll("\\.(jar|zip)$", "");
        StringBuilder sb = new StringBuilder();
        for (String tok : f.split("[-_+\\s]+")) {
            String t = tok.toLowerCase(Locale.ROOT);
            if (t.isEmpty() || t.matches(".*\\d.*")) continue;
            if (t.equals("fabric") || t.equals("forge") || t.equals("neoforge") || t.equals("quilt")
                    || t.equals("mc") || t.equals("mod")) continue;
            sb.append(sb.isEmpty() ? "" : " ").append(tok);
        }
        return sb.toString().trim();
    }

    private boolean looseMatch(String term, String name, String slug) {
        String t = norm(term), n = norm(name), s = norm(slug);
        if (t.isEmpty()) return false;
        return n.contains(t) || t.contains(n) || s.contains(t) || t.contains(s);
    }

    private static String norm(String s) {
        return s == null ? "" : s.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
    }

    private static String sha1(Path file) throws Exception {
        java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-1");
        byte[] hash = md.digest(Files.readAllBytes(file));
        StringBuilder sb = new StringBuilder(hash.length * 2);
        for (byte b : hash) sb.append(String.format("%02x", b));
        return sb.toString();
    }

    public Path folder(InstanceConfig cfg, ContentType type) {
        return LauncherPaths.instanceGameDir(cfg.id).resolve(type.folder);
    }

    /** Calcula las actualizaciones disponibles para el contenido instalado con proyecto vinculado. */
    public List<UpdateInfo> updates(InstanceConfig cfg) {
        ContentManifest manifest = ContentManifest.load(cfg.id);
        List<UpdateInfo> out = new ArrayList<>();
        for (ContentManifest.Entry e : manifest.items) {
            if (e.projectId == null || e.projectId.isBlank() || e.versionId == null) continue;
            try {
                ContentType type = ContentType.from(e.type);
                List<Version> versions = service.versions(e.source, type, e.projectId, cfg.mcVersion, cfg.loader, false);
                if (versions.isEmpty()) continue;
                Version latest = newest(versions);
                if (latest != null && !latest.id().equals(e.versionId))
                    out.add(new UpdateInfo(e.fileName, e.type, latest.id(), latest.versionNumber()));
            } catch (Exception ex) {
                log.debug("No se pudo comprobar update de {}: {}", e.projectId, ex.getMessage());
            }
        }
        return out;
    }

    private Version newest(List<Version> versions) {
        Version best = null;
        for (Version v : versions) {
            String d = v.datePublished() == null ? "" : v.datePublished();
            if (best == null || d.compareTo(best.datePublished() == null ? "" : best.datePublished()) > 0) best = v;
        }
        return best;
    }

    private InstalledItem toItem(ContentManifest.Entry e) {
        boolean enabled = !e.fileName.endsWith(".disabled");
        return new InstalledItem(e.fileName, e.type, enabled, e.source, e.projectId, e.slug,
                e.name, e.iconUrl, e.author, e.versionId, e.versionName,
                e.categories != null ? e.categories : List.of(),
                e.clientSide != null ? e.clientSide : "", e.serverSide != null ? e.serverSide : "");
    }

    private ProjectDetail safeDetail(String source, ContentType type, String projectId) {
        try {
            return service.project(source, type, projectId);
        } catch (Exception e) {
            return null;
        }
    }

    private boolean deleteIfExists(Path dir, String fileName) {
        try {
            String base = fileName.endsWith(".disabled") ? fileName.substring(0, fileName.length() - 9) : fileName;
            boolean a = Files.deleteIfExists(dir.resolve(base));
            boolean b = Files.deleteIfExists(dir.resolve(base + ".disabled"));
            return a || b;
        } catch (Exception e) {
            return false;
        }
    }

    private boolean isContentFile(String fn) {
        String f = fn.endsWith(".disabled") ? fn.substring(0, fn.length() - 9) : fn;
        return f.endsWith(".jar") || f.endsWith(".zip");
    }

    private String baseKey(String type, String fileName) {
        String base = fileName.endsWith(".disabled") ? fileName.substring(0, fileName.length() - 9) : fileName;
        return type + "/" + base;
    }

    private static String sanitize(String fileName) {
        String f = fileName.replaceAll("[\\\\/:*?\"<>|]", "_").trim();
        return f.isEmpty() ? "download-" + System.currentTimeMillis() + ".jar" : f;
    }
}
