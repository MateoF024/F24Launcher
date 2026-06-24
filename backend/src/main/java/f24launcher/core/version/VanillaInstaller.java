package f24launcher.core.version;

import f24launcher.core.LauncherPaths;
import f24launcher.core.meta.MojangMeta;
import f24launcher.core.meta.MojangMeta.*;
import f24launcher.util.DownloadManager;
import f24launcher.util.HttpConnectionPool;

import com.google.gson.Gson;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/**
 * Descarga e instala versiones vanilla de Minecraft a la caché compartida
 * (versions/, libraries/, assets/). Las descargas pasan por el {@link DownloadManager}
 * (paralelas, escritura atómica, idempotentes). La extracción de natives y el
 * lanzamiento se hacen en el motor de lanzamiento.
 */
public class VanillaInstaller {

    private static final Logger log = LoggerFactory.getLogger(VanillaInstaller.class);
    private final Gson gson = new Gson();
    private final HttpConnectionPool http = HttpConnectionPool.getInstance();
    private final DownloadManager dm = DownloadManager.getInstance();

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
            Artifact c = v.downloads.client;
            DownloadManager.Result r = dm.download(
                    new DownloadManager.Task(c.url, LauncherPaths.versionJar(mcVersion), c.size, c.sha1, null), false);
            if (r.status() == DownloadManager.Status.FAILED)
                throw new IOException("No se pudo descargar el cliente", r.error());
        }
        sink.update("Cliente", 1, 1);

        // 2) Librerías aplicables (Windows)
        List<DownloadManager.Task> libTasks = new ArrayList<>();
        for (Library lib : v.libraries) {
            if (!LibraryRules.usableOnWindows(lib)) continue;
            if (lib.downloads != null && lib.downloads.artifact != null && lib.downloads.artifact.path != null) {
                Artifact a = lib.downloads.artifact;
                libTasks.add(new DownloadManager.Task(a.url, LauncherPaths.library(a.path), a.size, a.sha1, null));
            }
            // Natives clásicos (<1.19): clasificador por SO
            Artifact nat = LibraryRules.classicNativeArtifact(lib);
            if (nat != null && nat.path != null) {
                libTasks.add(new DownloadManager.Task(nat.url, LauncherPaths.library(nat.path), nat.size, nat.sha1, null));
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
            List<DownloadManager.Task> assetTasks = new ArrayList<>();
            if (idx != null && idx.objects != null) {
                for (AssetObject obj : idx.objects.values()) {
                    String sub = obj.hash.substring(0, 2);
                    Path dest = LauncherPaths.assetObjects().resolve(sub).resolve(obj.hash);
                    // El nombre del objeto ES su sha1 → lo usamos para verificar integridad.
                    assetTasks.add(new DownloadManager.Task(
                            MojangMeta.RESOURCES_URL + sub + "/" + obj.hash, dest, obj.size, obj.hash, null));
                }
            }
            runParallel("Assets", assetTasks, sink);
        }

        log.info("Versión vanilla {} instalada.", mcVersion);
    }

    /** Descarga un lote por el {@link DownloadManager} y aborta si alguna falla. */
    private void runParallel(String phase, List<DownloadManager.Task> tasks, Sink sink) throws Exception {
        long total = tasks.size();
        sink.update(phase, 0, total);
        if (total == 0) return;
        List<DownloadManager.Result> results =
                dm.downloadAll(tasks, (done, tot) -> sink.update(phase, done, tot), false);
        DownloadManager.requireAllOk(results);
        sink.update(phase, total, total);
    }
}
