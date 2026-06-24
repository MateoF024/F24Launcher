package f24launcher.core.loader;

import f24launcher.core.LauncherPaths;
import f24launcher.core.meta.MojangMeta.*;
import f24launcher.core.version.MavenCoord;
import f24launcher.core.version.VanillaInstaller;
import f24launcher.instance.InstanceConfig;
import f24launcher.util.DownloadManager;

import com.google.gson.Gson;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** Instala y resuelve mod loaders (Fabric/Quilt) fusionando su perfil con la vanilla. */
public class LoaderInstaller {

    private static final Logger log = LoggerFactory.getLogger(LoaderInstaller.class);
    private final Gson gson = new Gson();
    private final DownloadManager dm = DownloadManager.getInstance();
    private final VanillaInstaller vanilla = new VanillaInstaller();
    private final LoaderMeta meta = new LoaderMeta();
    private final ForgeInstaller forge = new ForgeInstaller();

    public boolean isVanilla(InstanceConfig cfg) {
        return cfg.loader == null || cfg.loader.isBlank() || "vanilla".equals(cfg.loader);
    }

    public List<String> listVersions(String type, String mcVersion) throws Exception {
        if (forge.supports(type)) return forge.listVersions(type, mcVersion);
        return meta.listVersions(type, mcVersion);
    }

    /** Versión efectiva: vanilla sola, o vanilla fusionada con el perfil del loader. */
    public VersionDetails resolveVersion(InstanceConfig cfg) throws IOException {
        VersionDetails base = vanilla.resolveVersion(cfg.mcVersion);
        if (isVanilla(cfg)) return base;
        VersionDetails prof = gson.fromJson(loadProfile(cfg), VersionDetails.class);
        if (prof.mainClass != null) base.mainClass = prof.mainClass;
        if (prof.libraries != null) {
            for (Library lib : prof.libraries) synthesizeDownload(lib);
            base.libraries.addAll(0, prof.libraries);
        }
        if (prof.arguments != null) {
            if (base.arguments == null) base.arguments = new Arguments();
            if (prof.arguments.game != null) {
                if (base.arguments.game == null) base.arguments.game = new java.util.ArrayList<>();
                base.arguments.game.addAll(prof.arguments.game);
            }
            if (prof.arguments.jvm != null) {
                if (base.arguments.jvm == null) base.arguments.jvm = new java.util.ArrayList<>();
                base.arguments.jvm.addAll(prof.arguments.jvm);
            }
        }
        return base;
    }

    /** Descarga vanilla + (si aplica) el loader. */
    public void install(InstanceConfig cfg, VanillaInstaller.Sink sink) throws Exception {
        vanilla.install(cfg.mcVersion, sink);
        if (isVanilla(cfg)) return;
        if (forge.supports(cfg.loader)) {
            forge.install(cfg, sink, profilePath(cfg));
            return;
        }
        VersionDetails prof = gson.fromJson(loadProfile(cfg), VersionDetails.class);
        if (prof.libraries == null) return;
        List<DownloadManager.Task> tasks = new ArrayList<>();
        for (Library lib : prof.libraries) {
            synthesizeDownload(lib);
            if (lib.downloads != null && lib.downloads.artifact != null) {
                Artifact a = lib.downloads.artifact;
                if (a.url != null && !a.url.isBlank())
                    tasks.add(new DownloadManager.Task(a.url, LauncherPaths.library(a.path), a.size, a.sha1, null));
            }
        }
        List<DownloadManager.Result> results =
                dm.downloadAll(tasks, (d, t) -> sink.update(cfg.loader, d, t), false);
        DownloadManager.requireAllOk(results);
        log.info("Loader {} {} instalado para MC {}.", cfg.loader, cfg.loaderVersion, cfg.mcVersion);
    }

    private void synthesizeDownload(Library lib) {
        if (lib.downloads != null && lib.downloads.artifact != null) return;
        if (lib.name == null || lib.url == null) return;
        String path = MavenCoord.path(lib.name);
        Artifact a = new Artifact();
        a.path = path;
        a.url = lib.url.endsWith("/") ? lib.url + path : lib.url + "/" + path;
        a.size = 0;
        LibraryDownloads d = new LibraryDownloads();
        d.artifact = a;
        lib.downloads = d;
    }

    private Path profilePath(InstanceConfig cfg) throws IOException {
        Path dir = LauncherPaths.root().resolve("loaders");
        Files.createDirectories(dir);
        return dir.resolve(cfg.loader + "-" + cfg.mcVersion + "-" + cfg.loaderVersion + ".json");
    }

    private String loadProfile(InstanceConfig cfg) throws IOException {
        Path cached = profilePath(cfg);
        if (Files.exists(cached) && Files.size(cached) > 0) return Files.readString(cached);
        if (meta.supported(cfg.loader)) {
            String json = meta.profileJson(cfg.loader, cfg.mcVersion, cfg.loaderVersion);
            Files.writeString(cached, json);
            return json;
        }
        throw new IOException("Perfil de " + cfg.loader + " no disponible; instala la instancia primero.");
    }
}
