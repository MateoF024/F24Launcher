package bundle.installer;

import bundle.config.ConfigParseException;
import bundle.config.ConfigParser;
import bundle.config.DownloadConfig;
import bundle.config.InstallerConfig;
import bundle.config.RemoteConfigLoader;
import bundle.download.DownloadException;
import bundle.download.DownloadManager;
import bundle.download.ProgressCallback;
import bundle.gui.BundleGuiApp;
import bundle.settings.AppSettings;
import bundle.util.ExtractionProgressTracker;
import bundle.util.OperatingSystem;
import bundle.util.HttpConnectionPool;
import bundle.util.StreamingZipExtractor;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.zip.ZipFile;

public final class BundleInstaller {
    public Path gameDir;
    public String selectedInstall = "";
    public final InstallerConfig installerConfig;
    public final BundleGuiApp gui;
    private final AppSettings appSettings;

    private static final ExecutorService EXECUTOR = Executors.newFixedThreadPool(
            Math.min(4, Runtime.getRuntime().availableProcessors()));

    public BundleInstaller() {
        this.appSettings = AppSettings.getInstance();

        JsonObject configObject = null;
        InstallerConfig cfg = null;

        try {
            configObject = RemoteConfigLoader.loadAndValidateRemoteConfig();
            if (configObject != null) {
                cfg = ConfigParser.parse(configObject);
            }
        } catch (Exception e) {
            System.err.println("Error al cargar configuracion remota: " + e.getMessage());
        }

        if (cfg == null) {
            InputStream configStream = BundleInstaller.class.getClassLoader().getResourceAsStream("installer_config.json");
            if (configStream != null) {
                try {
                    InputStreamReader reader = new InputStreamReader(configStream, StandardCharsets.UTF_8);
                    configObject = new Gson().fromJson(reader, JsonObject.class);
                    cfg = ConfigParser.parse(configObject);
                } catch (ConfigParseException e) {
                    cfg = new InstallerConfig.Builder().build();
                }
            } else {
                cfg = new InstallerConfig.Builder().build();
            }
        }

        this.installerConfig = cfg;

        if (!installerConfig.configNames.isEmpty()) {
            selectedInstall = installerConfig.configNames.get(0);
        }

        String lastGameDir = appSettings.getLastGameDir();
        if (!lastGameDir.isEmpty() && Files.exists(Paths.get(lastGameDir))) {
            this.gameDir = Paths.get(lastGameDir);
        } else {
            this.gameDir = OperatingSystem.getCurrent().getMCDir();
            appSettings.setLastGameDir(this.gameDir.toString());
        }

        this.gui = new BundleGuiApp(this);
    }

    public void openUI() {
        gui.open();
    }

    public void install(ProgressCallback progressCallback) throws IOException, DownloadException {
        if (this.gameDir == null) {
            throw new DownloadException("El directorio seleccionado esta vacio!");
        }

        if (appSettings.isAutoCleanup()) {
            cleanupPartialFiles(gameDir);
        }

        deleteSelectedDirectories(gameDir);

        DownloadConfig dlConfig = this.installerConfig.configs.get(selectedInstall);
        if (dlConfig == null) {
            throw new IllegalStateException("No se encontro una configuracion valida para la instalacion seleccionada: " + selectedInstall);
        }

        if (!Files.exists(gameDir)) {
            throw new DownloadException(String.format("El directorio '%s' no existe!", gameDir));
        }

        List<DownloadException> errors = DownloadManager.downloadFilesTo(gameDir, dlConfig, progressCallback);
        if (!errors.isEmpty()) {
            for (DownloadException e : errors) {
                e.printStackTrace();
            }
            throw new IOException("Errores durante la descarga, no se puede continuar.");
        }

        processZipFilesWithProgress(gameDir, progressCallback);
    }

    private void processZipFilesWithProgress(Path directory, ProgressCallback progressCallback) throws IOException {
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(directory, "*.zip")) {
            for (Path zipFile : stream) {
                long totalFiles = countFilesInZipOptimized(zipFile);
                ExtractionProgressTracker tracker = new ExtractionProgressTracker(
                        progressCallback, "Extracción", totalFiles);

                unzipFileWithProgress(zipFile, directory, tracker);
                Files.deleteIfExists(zipFile);

                tracker.complete();
            }
        }

        if (progressCallback != null) {
            progressCallback.onAllComplete();
        }
    }

    private long countFilesInZipOptimized(Path zipFilePath) {
        long zipSize = 0;
        try {
            zipSize = Files.size(zipFilePath);
        } catch (IOException e) {
            System.err.println("Error obteniendo tamaño de ZIP: " + e.getMessage());
        }

        if (zipSize > 500 * 1024 * 1024) {
            return estimateFileCount(zipSize);
        } else {
            return countFilesUsingZipFile(zipFilePath);
        }
    }

    private long estimateFileCount(long zipSize) {
        long estimatedFiles = zipSize / (512 * 1024);
        return Math.max(100, Math.min(10000, estimatedFiles));
    }

    private long countFilesUsingZipFile(Path zipFilePath) {
        try (ZipFile zipFile = new ZipFile(zipFilePath.toFile(), StandardCharsets.UTF_8)) {
            return zipFile.stream()
                    .filter(entry -> !entry.isDirectory())
                    .count();
        } catch (IOException e) {
            System.err.println("Error contando archivos en ZIP: " + e.getMessage());
            return estimateFileCount(0);
        }
    }

    private void unzipFileWithProgress(Path zipFilePath, Path targetDir, ExtractionProgressTracker tracker) throws IOException {
        List<String> foldersToInstall = appSettings.getFoldersToInstall();

        StreamingZipExtractor.extractWithProgress(
                zipFilePath, targetDir, foldersToInstall, tracker);
    }

    private void cleanupPartialFiles(Path directory) {
        try {
            if (Files.exists(directory) && Files.isDirectory(directory)) {
                Files.walkFileTree(directory, new SimpleFileVisitor<Path>() {
                    @Override
                    public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                        if (file.getFileName().toString().matches("dl-.*\\.part")) {
                            try {
                                Files.deleteIfExists(file);
                            } catch (IOException e) {
                                System.err.println("No se pudo borrar parcial: " + file + " -> " + e.getMessage());
                            }
                        }
                        return FileVisitResult.CONTINUE;
                    }
                });
            }
        } catch (IOException e) {
            System.err.println("Advertencia al limpiar parciales: " + e.getMessage());
        }
    }

    private void deleteSelectedDirectories(Path installDir) {
        List<String> foldersToPreserve = appSettings.getFoldersToPreserve();

        String[] allPossibleFolders = {
                "mods", "config", "resourcepacks", "shaderpacks", "schematics",
                "kubejs", "scripts", "defaultconfigs",
                ".fabric", "cache", ".cache"
        };

        for (String folder : allPossibleFolders) {
            if (!foldersToPreserve.contains(folder)) {
                try {
                    Path folderPath = installDir.resolve(folder);
                    if (Files.exists(folderPath)) {
                        deleteDirectoryRecursive(folderPath);
                    }
                } catch (IOException e) {
                    System.err.println("Error eliminando directorio " + folder + ": " + e.getMessage());
                }
            }
        }
    }

    private void deleteDirectoryRecursive(Path directory) throws IOException {
        if (!Files.exists(directory) || !Files.isDirectory(directory)) {
            return;
        }

        Files.walkFileTree(directory, new SimpleFileVisitor<Path>() {
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
        EXECUTOR.shutdown();
        try {
            if (!EXECUTOR.awaitTermination(30, TimeUnit.SECONDS)) {
                EXECUTOR.shutdownNow();
            }
        } catch (InterruptedException e) {
            EXECUTOR.shutdownNow();
            Thread.currentThread().interrupt();
        }

        HttpConnectionPool.getInstance().shutdown();
    }
}