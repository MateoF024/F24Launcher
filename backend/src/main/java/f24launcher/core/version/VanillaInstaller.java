package f24launcher.core.version;

import f24launcher.core.LauncherPaths;
import f24launcher.core.meta.MojangMeta;
import f24launcher.core.meta.MojangMeta.*;
import f24launcher.util.AppExecutors;
import f24launcher.util.HttpConnectionPool;

import com.google.gson.Gson;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Descarga e instala versiones vanilla de Minecraft a la caché compartida
 * (versions/, libraries/, assets/). Verifica por tamaño y descarga en paralelo.
 * La extracción de natives y el lanzamiento se hacen en el motor de lanzamiento.
 */
public class VanillaInstaller {

    private static final Logger log = LoggerFactory.getLogger(VanillaInstaller.class);
    private final Gson gson = new Gson();
    private final HttpConnectionPool http = HttpConnectionPool.getInstance();

    /** Callback de progreso: fase, completados, total. */
    public interface Sink {
        void update(String phase, long done, long total);
    }

    private static final Sink NOOP = (p, d, t) -> {};

    public Manifest fetchManifest() throws IOException {
        return gson.fromJson(http.get(MojangMeta.MANIFEST_URL), Manifest.class);
    }

    /** Resuelve y cachea el version JSON en versions/&lt;id&gt;/&lt;id&gt;.json. */
    public VersionDetails resolveVersion(String id) throws IOException {
        Path cached = LauncherPaths.versionJson(id);
        if (Files.exists(cached) && Files.size(cached) > 0) {
            return gson.fromJson(Files.readString(cached), VersionDetails.class);
        }
        Manifest manifest = fetchManifest();
        Version entry = manifest.versions.stream()
                .filter(v -> v.id.equals(id)).findFirst()
                .orElseThrow(() -> new IOException("Versión desconocida: " + id));
        String json = http.get(entry.url);
        Files.writeString(cached, json);
        return gson.fromJson(json, VersionDetails.class);
    }

    /** Descarga todo lo necesario para la versión (idempotente). */
    public void install(String mcVersion, Sink sink) throws Exception {
        if (sink == null) sink = NOOP;
        VersionDetails v = resolveVersion(mcVersion);

        // 1) client.jar
        sink.update("Cliente", 0, 1);
        if (v.downloads != null && v.downloads.client != null) {
            downloadFile(v.downloads.client.url, LauncherPaths.versionJar(mcVersion), v.downloads.client.size);
        }
        sink.update("Cliente", 1, 1);

        // 2) Librerías aplicables (Windows)
        List<DownloadTask> libTasks = new ArrayList<>();
        for (Library lib : v.libraries) {
            if (!LibraryRules.usableOnWindows(lib)) continue;
            if (lib.downloads != null && lib.downloads.artifact != null && lib.downloads.artifact.path != null) {
                Artifact a = lib.downloads.artifact;
                libTasks.add(new DownloadTask(a.url, LauncherPaths.library(a.path), a.size));
            }
            // Natives clásicos (<1.19): clasificador por SO
            Artifact nat = LibraryRules.classicNativeArtifact(lib);
            if (nat != null && nat.path != null) {
                libTasks.add(new DownloadTask(nat.url, LauncherPaths.library(nat.path), nat.size));
            }
        }
        runParallel("Librerías", libTasks, sink);

        // 3) Assets
        if (v.assetIndex != null) {
            Path idxFile = LauncherPaths.assetIndexes().resolve(v.assetIndex.id + ".json");
            String idxJson;
            if (Files.exists(idxFile) && Files.size(idxFile) > 0) {
                idxJson = Files.readString(idxFile);
            } else {
                idxJson = http.get(v.assetIndex.url);
                Files.writeString(idxFile, idxJson);
            }
            AssetIndexFile idx = gson.fromJson(idxJson, AssetIndexFile.class);
            List<DownloadTask> assetTasks = new ArrayList<>();
            if (idx != null && idx.objects != null) {
                for (AssetObject obj : idx.objects.values()) {
                    String sub = obj.hash.substring(0, 2);
                    Path dest = LauncherPaths.assetObjects().resolve(sub).resolve(obj.hash);
                    assetTasks.add(new DownloadTask(MojangMeta.RESOURCES_URL + sub + "/" + obj.hash, dest, obj.size));
                }
            }
            runParallel("Assets", assetTasks, sink);
        }

        log.info("Versión vanilla {} instalada.", mcVersion);
    }

    private void runParallel(String phase, List<DownloadTask> tasks, Sink sink) throws Exception {
        long total = tasks.size();
        sink.update(phase, 0, total);
        if (total == 0) return;
        AtomicLong done = new AtomicLong();
        List<CompletableFuture<Void>> futures = new ArrayList<>();
        for (DownloadTask t : tasks) {
            futures.add(CompletableFuture.runAsync(() -> {
                try {
                    downloadFile(t.url, t.dest, t.size);
                } catch (Exception e) {
                    throw new RuntimeException("Fallo descargando " + t.url + ": " + e.getMessage(), e);
                }
                long d = done.incrementAndGet();
                if (d % 25 == 0 || d == total) sink.update(phase, d, total);
            }, AppExecutors.download()));
        }
        CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
        sink.update(phase, total, total);
    }

    /** Descarga si falta o el tamaño no coincide. */
    private void downloadFile(String url, Path dest, long expectedSize) throws IOException {
        if (Files.exists(dest)) {
            if (expectedSize <= 0 || Files.size(dest) == expectedSize) return;
        }
        byte[] data = http.getBytes(url);
        Files.createDirectories(dest.getParent());
        Files.write(dest, data);
    }

    private record DownloadTask(String url, Path dest, long size) {}
}
