package f24launcher.content;

import f24launcher.content.ContentModels.*;
import f24launcher.core.LauncherPaths;
import f24launcher.core.version.VanillaInstaller;
import f24launcher.instance.InstanceConfig;
import f24launcher.util.DownloadManager;
import f24launcher.util.HttpConnectionPool;
import f24launcher.util.Murmur2;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.*;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.Stream;

/**
 * Instala, activa/desactiva y elimina contenido en una instancia, manteniendo
 * sincronizados los archivos de .minecraft con {@link ContentManifest}.
 */
public class ContentInstaller {

    private static final Logger log = LoggerFactory.getLogger(ContentInstaller.class);

    private final ContentService service;
    private final DownloadManager dm = DownloadManager.getInstance();

    public ContentInstaller(ContentService service) { this.service = service; }

    /** Instala un proyecto (versión concreta o la mejor compatible) y sus dependencias requeridas. */
    public InstalledItem install(InstanceConfig cfg, String source, ContentType type,
                                 String projectId, String versionId, boolean ignoreFilters) throws Exception {
        return install(cfg, source, type, projectId, versionId, ignoreFilters, (p, d, t) -> {});
    }

    /** Igual que {@link #install}, reportando el progreso de descarga por el sink (P9). */
    public InstalledItem install(InstanceConfig cfg, String source, ContentType type,
                                 String projectId, String versionId, boolean ignoreFilters,
                                 VanillaInstaller.Sink sink) throws Exception {
        ContentManifest manifest = ContentManifest.load(cfg.id);
        InstalledItem item = installInto(cfg, source, type, projectId, versionId, ignoreFilters, manifest, new HashSet<>(), sink);
        manifest.save(cfg.id);
        return item;
    }

    private InstalledItem installInto(InstanceConfig cfg, String source, ContentType type, String projectId,
                                      String versionId, boolean ignoreFilters, ContentManifest manifest,
                                      Set<String> visited, VanillaInstaller.Sink sink) throws Exception {
        String key = source + ":" + projectId + ":" + type.id;
        if (!visited.add(key)) return null;

        Version version = resolve(source, type, projectId, versionId, cfg, ignoreFilters);
        if (version == null) throw new IllegalStateException("Sin versión compatible para " + projectId);

        Path dir = folder(cfg, type);
        Files.createDirectories(dir);
        Path dest = dir.resolve(sanitize(version.fileName()));
        sink.update("Descargando " + version.fileName(), 0, 1);
        byte[] data = HttpConnectionPool.getInstance().getBytes(version.downloadUrl());
        String sha1 = hex(data, "SHA-1");
        ObjectStore.linkWrite(dest, data, sha1); // dedup: store + hardlink (cae a copia atómica si no se puede)

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
        if (e.addedAt == 0) e.addedAt = System.currentTimeMillis(); // se conserva al actualizar
        e.datePublished = version.datePublished() != null ? version.datePublished() : "";
        e.downloadUrl = version.downloadUrl();
        e.fileSize = data.length;
        e.sha1 = sha1;
        e.sha512 = hex(data, "SHA-512");
        sink.update("Instalado " + e.name, 1, 1);

        // Dependencias requeridas (se tratan como mods).
        for (Dependency dep : version.dependencies()) {
            if (!"required".equalsIgnoreCase(dep.type())) continue;
            if (manifest.find(source, dep.projectId(), ContentType.MODS.id) != null) continue;
            try {
                installInto(cfg, source, ContentType.MODS, dep.projectId(), null, ignoreFilters, manifest, visited, sink);
            } catch (Exception ex) {
                log.warn("No se pudo instalar dependencia {}: {}", dep.projectId(), ex.getMessage());
            }
        }

        log.info("Instalado {} ({}) en {}", e.name, version.versionNumber(), cfg.id);
        return toItem(cfg, e);
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
        for (ContentManifest.Entry e : manifest.items) byBase.put(baseKey(e.type, e.fileName), toItem(cfg, e));

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
                            List.of(), "", "", fileMtime(p), ""));
                });
            } catch (Exception ignored) {}
        }
        List<InstalledItem> out = new ArrayList<>(byBase.values());
        out.sort(Comparator.comparing(InstalledItem::type).thenComparing(i -> i.name().toLowerCase()));
        return out;
    }

    /**
     * Identifica los mods/archivos añadidos manualmente (sin entrada en el
     * manifiesto). Lee cada archivo una vez (SHA1 + fingerprint murmur2) y consulta
     * en lote Modrinth (por SHA1) y CurseForge (por fingerprint, identificación
     * exacta). Persiste lo reconocido, con los datos para reparar/exportar.
     */
    public List<InstalledItem> identify(InstanceConfig cfg) {
        long t0 = System.nanoTime();
        ContentManifest manifest = ContentManifest.load(cfg.id);
        List<Pending> pending = new ArrayList<>();
        for (ContentType type : ContentType.values()) {
            Path dir = folder(cfg, type);
            if (!Files.isDirectory(dir)) continue;
            try (Stream<Path> files = Files.list(dir)) {
                for (Path p : files.filter(Files::isRegularFile).toList()) {
                    String fn = p.getFileName().toString();
                    if (!isContentFile(fn) || manifest.findByFile(fn) != null) continue;
                    try {
                        byte[] data = Files.readAllBytes(p);
                        pending.add(new Pending(type, fn, hex(data, "SHA-1"),
                                Murmur2.curseForgeFingerprint(data), data.length, fileMtime(p)));
                    } catch (Exception ex) {
                        log.debug("No se pudo leer {}: {}", fn, ex.getMessage());
                    }
                }
            } catch (Exception ignored) {}
        }
        if (pending.isEmpty()) return list(cfg);

        Set<String> sha1s = new HashSet<>();
        Set<Long> fingerprints = new HashSet<>();
        for (Pending p : pending) { sha1s.add(p.sha1()); fingerprints.add(p.fingerprint()); }
        Map<String, ContentService.ModrinthHit> mr = service.modrinthByHashes(sha1s);
        Map<Long, ContentService.CfMatch> cf = service.curseForgeByFingerprints(fingerprints);
        log.info("identify {}: {} archivo(s) sin identificar · Modrinth {} · CurseForge {}",
                cfg.id, pending.size(), mr.size(), cf.size());

        int identified = 0;
        for (Pending p : pending) {
            ContentManifest.Entry e = null;
            ContentService.ModrinthHit h = mr.get(p.sha1());
            if (h != null) {
                ProjectDetail d = safeDetail("modrinth", p.type(), h.projectId());
                e = entryFrom(p.fileName(), p.type(), "modrinth", h.projectId(), h.versionId(), h.versionNumber(), d);
                fillExport(e, h.downloadUrl(), p.sha1(), h.sha512(), p.size());
            } else {
                ContentService.CfMatch m = cf.get(p.fingerprint());
                if (m != null) {
                    ProjectDetail d = safeDetail("curseforge", p.type(), m.projectId());
                    e = entryFrom(p.fileName(), p.type(), "curseforge", m.projectId(), m.fileId(), m.fileName(), d);
                    fillExport(e, m.downloadUrl(), p.sha1(), "", p.size());
                }
            }
            if (e != null) { e.addedAt = p.mtime() > 0 ? p.mtime() : System.currentTimeMillis(); manifest.items.add(e); identified++; }
        }
        if (identified > 0) manifest.save(cfg.id);
        log.info("identify {}: {} identificado(s) en {} ms", cfg.id, identified, (System.nanoTime() - t0) / 1_000_000);
        return list(cfg);
    }

    /** Archivo pendiente de identificar: tipo, nombre, sha1, fingerprint CF, tamaño y mtime. */
    private record Pending(ContentType type, String fileName, String sha1, long fingerprint, long size, long mtime) {}

    /** Rellena los datos de reparación/export (downloadUrl + hashes + tamaño) de un mod identificado. */
    private void fillExport(ContentManifest.Entry e, String downloadUrl, String sha1, String sha512, long size) {
        e.downloadUrl = downloadUrl != null ? downloadUrl : "";
        e.sha1 = sha1 != null ? sha1 : "";
        e.sha512 = sha512 != null ? sha512 : "";
        e.fileSize = size;
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

    /** Hash hexadecimal de unos bytes (p. ej. SHA-1 / SHA-512). "" si falla. */
    public static String hex(byte[] data, String algorithm) {
        try {
            byte[] h = java.security.MessageDigest.getInstance(algorithm).digest(data);
            StringBuilder sb = new StringBuilder(h.length * 2);
            for (byte b : h) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) {
            return "";
        }
    }

    public Path folder(InstanceConfig cfg, ContentType type) {
        return LauncherPaths.instanceGameDir(cfg.id).resolve(type.folder);
    }

    /**
     * Copia archivos sueltos (.jar/.zip) a la carpeta de contenido de la instancia
     * y luego identifica los nuevos. Si {@code forcedType} viene vacío, se deduce por
     * extensión (.jar → mods, .zip → resourcepacks). Devuelve la lista resultante.
     */
    public List<InstalledItem> importFiles(InstanceConfig cfg, List<String> paths, String forcedType) {
        ContentType forced = (forcedType != null && !forcedType.isBlank()) ? ContentType.from(forcedType) : null;
        for (String ps : paths) {
            try {
                Path src = Paths.get(ps);
                if (!Files.isRegularFile(src)) continue;
                String fn = src.getFileName().toString();
                ContentType type = forced != null ? forced : inferType(fn);
                if (type == null) continue;
                Path dir = folder(cfg, type);
                Files.createDirectories(dir);
                Files.copy(src, dir.resolve(sanitize(fn)), StandardCopyOption.REPLACE_EXISTING);
                log.info("Importado {} a {} ({})", fn, cfg.id, type.id);
            } catch (Exception e) {
                log.warn("No se pudo importar {}: {}", ps, e.getMessage());
            }
        }
        return identify(cfg);
    }

    /**
     * Verifica el contenido instalado contra los hashes guardados y vuelve a
     * descargar lo que falte o esté corrupto. Devuelve cuántos archivos se repararon.
     */
    public int repair(InstanceConfig cfg, VanillaInstaller.Sink sink) {
        ContentManifest manifest = ContentManifest.load(cfg.id);
        List<DownloadManager.Task> tasks = new ArrayList<>();
        for (ContentManifest.Entry e : manifest.items) {
            if (e.downloadUrl == null || e.downloadUrl.isBlank() || e.sha1 == null || e.sha1.isBlank()) continue;
            Path file = folder(cfg, ContentType.from(e.type)).resolve(e.fileName);
            tasks.add(new DownloadManager.Task(e.downloadUrl, file, e.fileSize, e.sha1, e.sha512));
        }
        if (tasks.isEmpty()) { sink.update("Verificando contenido", 0, 0); return 0; }
        // verifyExisting=true → comprueba el hash de lo presente y re-descarga lo que falte o no cuadre (con dedup).
        List<DownloadManager.Result> results =
                dm.downloadAll(tasks, (d, t) -> sink.update("Verificando contenido", d, t), true, ObjectStore::linkWriteFile);
        int fixed = 0;
        for (DownloadManager.Result r : results) if (r.status() == DownloadManager.Status.DOWNLOADED) fixed++;
        log.info("Reparación de {}: {} archivo(s) restaurado(s) de {} comprobados.", cfg.id, fixed, tasks.size());
        return fixed;
    }

    private ContentType inferType(String fileName) {
        String f = fileName.toLowerCase(Locale.ROOT);
        if (f.endsWith(".jar")) return ContentType.MODS;
        if (f.endsWith(".zip")) return ContentType.RESOURCEPACKS;
        return null;
    }

    /** Calcula las actualizaciones disponibles para el contenido instalado con proyecto vinculado (en paralelo). */
    public List<UpdateInfo> updates(InstanceConfig cfg) {
        long t0 = System.nanoTime();
        ContentManifest manifest = ContentManifest.load(cfg.id);
        List<UpdateInfo> out = new ArrayList<>();
        int checked = 0;
        try (ExecutorService exec = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<UpdateInfo>> futures = new ArrayList<>();
            for (ContentManifest.Entry e : manifest.items) {
                if (e.projectId == null || e.projectId.isBlank() || e.versionId == null) continue;
                checked++;
                futures.add(exec.submit(() -> updateOne(e, cfg)));
            }
            for (Future<UpdateInfo> f : futures) {
                try {
                    UpdateInfo u = f.get();
                    if (u != null) out.add(u);
                } catch (Exception ignored) {}
            }
        }
        log.info("updates {}: {} con actualización de {} comprobado(s) en {} ms",
                cfg.id, out.size(), checked, (System.nanoTime() - t0) / 1_000_000);
        return out;
    }

    private UpdateInfo updateOne(ContentManifest.Entry e, InstanceConfig cfg) {
        try {
            ContentType type = ContentType.from(e.type);
            List<Version> versions = service.versions(e.source, type, e.projectId, cfg.mcVersion, cfg.loader, false);
            if (versions.isEmpty()) return null;
            Version latest = newest(versions);
            if (latest != null && !latest.id().equals(e.versionId))
                return new UpdateInfo(e.fileName, e.type, latest.id(), latest.versionNumber());
            return null;
        } catch (Exception ex) {
            log.debug("No se pudo comprobar update de {}: {}", e.projectId, ex.getMessage());
            return null;
        }
    }

    /**
     * Comprueba (sin aplicar nada) la compatibilidad del contenido instalado con
     * una versión/loader objetivo. Para mods usa el loader objetivo; resourcepacks,
     * shaders y datapacks no dependen del loader. Ver {@link CompatItem}.
     */
    public List<CompatItem> compat(InstanceConfig cfg, String targetMc, String targetLoader) {
        long t0 = System.nanoTime();
        ContentManifest manifest = ContentManifest.load(cfg.id);
        List<CompatItem> out = new ArrayList<>();
        try (ExecutorService exec = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<CompatItem>> futures = new ArrayList<>();
            for (ContentManifest.Entry e : manifest.items) {
                futures.add(exec.submit(() -> compatOne(e, targetMc, targetLoader)));
            }
            for (Future<CompatItem> f : futures) {
                try { out.add(f.get()); } catch (Exception ignored) {}
            }
        }
        log.info("compat {} → MC {} · loader {}: {} ítem(s) evaluados en {} ms",
                cfg.id, targetMc, targetLoader, out.size(), (System.nanoTime() - t0) / 1_000_000);
        return out;
    }

    private CompatItem compatOne(ContentManifest.Entry e, String targetMc, String targetLoader) {
        ContentType type = ContentType.from(e.type);
        if (e.projectId == null || e.projectId.isBlank() || e.source == null || e.source.isBlank())
            return new CompatItem(e.fileName, e.type, e.name, "unknown", "", "");
        try {
            List<Version> versions = service.versions(e.source, type, e.projectId, targetMc, targetLoader, false);
            if (versions.isEmpty())
                return new CompatItem(e.fileName, e.type, e.name, "incompatible", "", "");
            Version latest = newest(versions);
            boolean currentOk = e.versionId != null
                    && versions.stream().anyMatch(v -> v.id().equals(e.versionId));
            if (currentOk && latest != null && latest.id().equals(e.versionId))
                return new CompatItem(e.fileName, e.type, e.name, "compatible", e.versionId, e.versionName);
            String vid = latest != null ? latest.id() : "";
            String vname = latest != null ? latest.versionNumber() : "";
            return new CompatItem(e.fileName, e.type, e.name, "updatable", vid, vname);
        } catch (Exception ex) {
            log.debug("compat {}: {}", e.projectId, ex.getMessage());
            return new CompatItem(e.fileName, e.type, e.name, "unknown", "", "");
        }
    }

    private Version newest(List<Version> versions) {
        Version best = null;
        for (Version v : versions) {
            String d = v.datePublished() == null ? "" : v.datePublished();
            if (best == null || d.compareTo(best.datePublished() == null ? "" : best.datePublished()) > 0) best = v;
        }
        return best;
    }

    private InstalledItem toItem(InstanceConfig cfg, ContentManifest.Entry e) {
        boolean enabled = !e.fileName.endsWith(".disabled");
        long addedAt = e.addedAt > 0 ? e.addedAt
                : fileMtime(folder(cfg, ContentType.from(e.type)).resolve(e.fileName));
        return new InstalledItem(e.fileName, e.type, enabled, e.source, e.projectId, e.slug,
                e.name, e.iconUrl, e.author, e.versionId, e.versionName,
                e.categories != null ? e.categories : List.of(),
                e.clientSide != null ? e.clientSide : "", e.serverSide != null ? e.serverSide : "",
                addedAt, e.datePublished != null ? e.datePublished : "");
    }

    /** Fecha de modificación del archivo (epoch ms), o 0 si no se puede leer. */
    private static long fileMtime(Path p) {
        try {
            return Files.getLastModifiedTime(p).toMillis();
        } catch (Exception e) {
            return 0;
        }
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
