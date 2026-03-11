package bundle.installer;

import bundle.config.*;
import bundle.download.*;
import bundle.gui.BundleGuiApp;
import bundle.loader.LoaderManager;
import bundle.settings.AppSettings;
import bundle.util.*;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.swing.SwingUtilities;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.zip.ZipFile;

public final class BundleInstaller {

    private static final Logger log = LoggerFactory.getLogger(BundleInstaller.class);

    private volatile Path gameDir;
    private volatile InstallerConfig installerConfig;
    public String selectedInstall = "";

    public final BundleGuiApp gui;
    private final AppSettings appSettings;

    public BundleInstaller() {
        this.appSettings = AppSettings.getInstance();

        // #19/#20 — Cargar solo config local en el constructor (sin red)
        this.installerConfig = loadLocalConfig();

        if (!installerConfig.configNames.isEmpty()) {
            selectedInstall = installerConfig.configNames.get(0);
        }

        String lastDir = appSettings.getLastGameDir();
        if (!lastDir.isEmpty() && Files.exists(Paths.get(lastDir))) {
            this.gameDir = Paths.get(lastDir);
        } else {
            this.gameDir = OperatingSystem.getCurrent().getMCDir();
            appSettings.setLastGameDir(this.gameDir.toString());
        }

        this.gui = new BundleGuiApp(this);
    }

    public void openUI() {
        gui.open();
        loadRemoteConfigAsync(); // #20 — carga remota después de mostrar UI
    }

    // #20 — Carga remota en background, actualiza UI al terminar
    private void loadRemoteConfigAsync() {
        CompletableFuture.supplyAsync(() -> {
            try {
                log.info("Iniciando carga remota de configuración...");
                JsonObject json = RemoteConfigLoader.loadAndValidateRemoteConfig();
                if (json != null) {
                    return ConfigParser.parse(json);
                }
            } catch (Exception e) {
                log.warn("Error cargando configuración remota: {}", e.getMessage());
            }
            return null;
        }).thenAccept(cfg -> {
            if (cfg != null && !cfg.configNames.isEmpty()) {
                updateInstallerConfig(cfg);
                selectedInstall = cfg.configNames.get(0);
                log.info("Configuración remota aplicada: {} modpack(s)", cfg.configNames.size());
                SwingUtilities.invokeLater(() -> gui.onConfigLoaded(cfg));
            }
        });
    }

    private InstallerConfig loadLocalConfig() {
        InputStream stream = BundleInstaller.class.getClassLoader()
                .getResourceAsStream("installer_config.json");
        if (stream != null) {
            try {
                InputStreamReader reader = new InputStreamReader(stream, StandardCharsets.UTF_8);
                JsonObject json = new Gson().fromJson(reader, JsonObject.class);
                InstallerConfig cfg = ConfigParser.parse(json);
                log.info("Configuración local cargada: {} modpack(s)", cfg.configNames.size());
                return cfg;
            } catch (ConfigParseException e) {
                log.warn("Error parseando configuración local: {}", e.getMessage());
            }
        }
        log.warn("No se encontró installer_config.json en resources, usando config vacía");
        return new InstallerConfig.Builder().build();
    }

    public synchronized Path getGameDir() {
        return gameDir;
    }

    public synchronized void setGameDir(Path path) {
        this.gameDir = path;
    }

    public synchronized InstallerConfig getInstallerConfig() {
        if (installerConfig == null) return new InstallerConfig.Builder().build();
        return installerConfig;
    }

    public synchronized void updateInstallerConfig(InstallerConfig config) {
        if (config != null) this.installerConfig = config;
    }

    public void install(ProgressCallback progressCallback) throws IOException, DownloadException {
        Path dir = getGameDir();
        InstallerConfig cfg = getInstallerConfig();

        if (dir == null) throw new DownloadException("El directorio seleccionado está vacío");

        if (appSettings.isAutoCleanup()) cleanupPartialFiles(dir);

        deleteSelectedDirectories(dir);

        DownloadConfig dlConfig = cfg.configs.get(selectedInstall);
        if (dlConfig == null) {
            throw new IllegalStateException("No hay configuración para: " + selectedInstall);
        }

        if (!Files.exists(dir)) {
            throw new DownloadException(String.format("El directorio '%s' no existe", dir));
        }

        List<DownloadException> errors = DownloadManager.downloadFilesTo(dir, dlConfig, progressCallback);
        if (!errors.isEmpty()) {
            errors.forEach(e -> log.error("Error de descarga: {}", e.getMessage()));
            throw new IOException("Errores durante la descarga, no se puede continuar.");
        }

        processZipFilesWithProgress(dir, progressCallback);
    }

    private void processZipFilesWithProgress(Path directory, ProgressCallback progressCallback) throws IOException {
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(directory, "*.zip")) {
            for (Path zipFile : stream) {
                long estimated = estimateFileCount(getZipSize(zipFile));
                ExtractionProgressTracker tracker = new ExtractionProgressTracker(
                        progressCallback, "Extracción", estimated);

                StreamingZipExtractor.extractWithProgress(zipFile, directory,
                        appSettings.getFoldersToInstall(), tracker);

                Files.deleteIfExists(zipFile);
                tracker.complete();
            }
        }
        if (progressCallback != null) progressCallback.onAllComplete();
    }

    private long getZipSize(Path zipFile) {
        try { return Files.size(zipFile); }
        catch (IOException e) { return 0; }
    }

    private long estimateFileCount(long zipSize) {
        long estimated = zipSize / (512 * 1024);
        return Math.max(100, Math.min(10000, estimated));
    }

    private void cleanupPartialFiles(Path directory) {
        try {
            if (Files.exists(directory) && Files.isDirectory(directory)) {
                Files.walkFileTree(directory, new SimpleFileVisitor<>() {
                    @Override
                    public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                        if (file.getFileName().toString().matches("dl-.*\\.part")) {
                            try { Files.deleteIfExists(file); }
                            catch (IOException e) {
                                log.warn("No se pudo borrar parcial: {} - {}", file, e.getMessage());
                            }
                        }
                        return FileVisitResult.CONTINUE;
                    }
                });
            }
        } catch (IOException e) {
            log.warn("Advertencia al limpiar parciales: {}", e.getMessage());
        }
    }

    private void deleteSelectedDirectories(Path installDir) {
        List<String> preserve = appSettings.getFoldersToPreserve();
        String[] allFolders = {
                "mods", "config", "resourcepacks", "shaderpacks", "schematics",
                "kubejs", "scripts", "defaultconfigs", ".fabric", "cache", ".cache"
        };
        for (String folder : allFolders) {
            if (!preserve.contains(folder)) {
                try {
                    Path path = installDir.resolve(folder);
                    if (Files.exists(path)) deleteDirectoryRecursive(path);
                } catch (IOException e) {
                    log.error("Error eliminando directorio {}: {}", folder, e.getMessage());
                }
            }
        }
    }

    private void deleteDirectoryRecursive(Path directory) throws IOException {
        if (!Files.exists(directory) || !Files.isDirectory(directory)) return;
        Files.walkFileTree(directory, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                Files.delete(file);
                return FileVisitResult.CONTINUE;
            }
            @Override
            public FileVisitResult postVisitDirectory(Path dir, IOException exc) throws IOException {
                if (exc != null) throw exc;
                Files.delete(dir);
                return FileVisitResult.CONTINUE;
            }
        });
    }

    public static void shutdown() {
        HttpConnectionPool.getInstance().shutdown();
        LoaderManager.shutdown();
    }
}