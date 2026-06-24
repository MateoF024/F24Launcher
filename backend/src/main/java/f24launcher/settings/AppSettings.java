package f24launcher.settings;

import f24launcher.util.DownloadManager;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.annotations.Expose;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Ajustes globales del launcher (no por instancia): tema, valores por defecto
 * de nuevas instancias (memoria, JVM, resolución) y tamaño de la ventana del
 * propio launcher.
 */
public class AppSettings {

    private static final String SETTINGS_FILE = "launcher.json";
    private static final Path SETTINGS_PATH = getSettingsPath();
    private static AppSettings instance;

    @Expose private boolean darkMode = true;
    @Expose private boolean showBetaVersions = false;
    @Expose private String lastDir = "";

    // Ruta personalizada de la carpeta de instancias (vacío = por defecto en appdata).
    @Expose private String instancesPath = "";

    // Valores por defecto aplicados a cada instancia nueva.
    @Expose private int defaultMinMemoryMb = 1024;
    @Expose private int defaultMaxMemoryMb = 4096;
    @Expose private int defaultWindowWidth = 854;
    @Expose private int defaultWindowHeight = 480;
    @Expose private String defaultJvmArgs = "";

    // Tamaño por defecto de la ventana del launcher (16:9).
    @Expose private int launcherWidth = 1440;
    @Expose private int launcherHeight = 810;

    // Comportamiento de ventana.
    @Expose private boolean closeToBackground = false;  // cerrar (X) oculta a la bandeja en vez de salir
    @Expose private boolean minimizeOnLaunch = false;   // minimizar al lanzar una instancia; restaurar al terminar

    // Concurrencia de descargas/escrituras (Fase B). Se leen solo al arrancar
    // (cambiarlos requiere reiniciar). Descargas es network-bound → valor fijo;
    // escrituras es disk-bound + hashing → por defecto autodetectado por núcleos.
    @Expose private int maxConcurrentDownloads = DownloadManager.defaultLimits()[0];
    @Expose private int maxConcurrentWrites = DownloadManager.defaultLimits()[1];

    private AppSettings() {}

    public static synchronized AppSettings getInstance() {
        if (instance == null) {
            instance = loadSettings();
        }
        return instance;
    }

    private static Path getSettingsPath() {
        String userHome = System.getProperty("user.home");
        Path appDir = Paths.get(userHome, "AppData", "Roaming", "F24Launcher");
        try {
            Files.createDirectories(appDir);
        } catch (Exception e) {
            return Paths.get(System.getProperty("java.io.tmpdir"), SETTINGS_FILE);
        }
        return appDir.resolve(SETTINGS_FILE);
    }

    private static AppSettings loadSettings() {
        if (Files.exists(SETTINGS_PATH)) {
            try (Reader reader = Files.newBufferedReader(SETTINGS_PATH)) {
                Gson gson = new GsonBuilder().excludeFieldsWithoutExposeAnnotation().create();
                AppSettings settings = gson.fromJson(reader, AppSettings.class);
                if (settings != null) return settings;
            } catch (Exception e) {
                System.err.println("Error cargando configuraciones: " + e.getMessage());
            }
        }
        AppSettings defaultSettings = new AppSettings();
        defaultSettings.save();
        return defaultSettings;
    }

    public synchronized void save() {
        try {
            Files.createDirectories(SETTINGS_PATH.getParent());
            try (Writer writer = Files.newBufferedWriter(SETTINGS_PATH)) {
                Gson gson = new GsonBuilder()
                        .excludeFieldsWithoutExposeAnnotation()
                        .setPrettyPrinting()
                        .disableHtmlEscaping()
                        .create();
                gson.toJson(this, writer);
            }
        } catch (Exception e) {
            System.err.println("Error guardando configuraciones: " + e.getMessage());
        }
    }

    // ── Patch parcial desde la IPC ──
    public synchronized void apply(Patch p) {
        if (p == null) return;
        if (p.darkMode != null) darkMode = p.darkMode;
        if (p.showBetaVersions != null) showBetaVersions = p.showBetaVersions;
        if (p.instancesPath != null) instancesPath = p.instancesPath.trim();
        if (p.defaultMinMemoryMb != null) defaultMinMemoryMb = clamp(p.defaultMinMemoryMb, 256, 65536);
        if (p.defaultMaxMemoryMb != null) defaultMaxMemoryMb = clamp(p.defaultMaxMemoryMb, 512, 65536);
        if (defaultMaxMemoryMb < defaultMinMemoryMb) defaultMaxMemoryMb = defaultMinMemoryMb;
        if (p.defaultWindowWidth != null) defaultWindowWidth = Math.max(640, p.defaultWindowWidth);
        if (p.defaultWindowHeight != null) defaultWindowHeight = Math.max(480, p.defaultWindowHeight);
        if (p.defaultJvmArgs != null) defaultJvmArgs = p.defaultJvmArgs.trim();
        if (p.launcherWidth != null) launcherWidth = clamp(p.launcherWidth, 1024, 3840);
        if (p.launcherHeight != null) launcherHeight = clamp(p.launcherHeight, 576, 2160);
        if (p.closeToBackground != null) closeToBackground = p.closeToBackground;
        if (p.minimizeOnLaunch != null) minimizeOnLaunch = p.minimizeOnLaunch;
        if (p.maxConcurrentDownloads != null) maxConcurrentDownloads = clamp(p.maxConcurrentDownloads, 1, 16);
        if (p.maxConcurrentWrites != null) maxConcurrentWrites = clamp(p.maxConcurrentWrites, 1, 32);
        save();
    }

    /** Campos opcionales del PATCH /settings. */
    public static final class Patch {
        public Boolean darkMode;
        public Boolean showBetaVersions;
        public String instancesPath;
        public Integer defaultMinMemoryMb;
        public Integer defaultMaxMemoryMb;
        public Integer defaultWindowWidth;
        public Integer defaultWindowHeight;
        public String defaultJvmArgs;
        public Integer launcherWidth;
        public Integer launcherHeight;
        public Boolean closeToBackground;
        public Boolean minimizeOnLaunch;
        public Integer maxConcurrentDownloads;
        public Integer maxConcurrentWrites;
    }

    private static int clamp(int v, int min, int max) { return Math.max(min, Math.min(max, v)); }

    public boolean isDarkMode() { return darkMode; }
    public boolean isShowBetaVersions() { return showBetaVersions; }
    public String getInstancesPath() { return instancesPath == null ? "" : instancesPath; }
    public String getLastDir() { return lastDir == null ? "" : lastDir; }
    public int getDefaultMinMemoryMb() { return defaultMinMemoryMb; }
    public int getDefaultMaxMemoryMb() { return defaultMaxMemoryMb; }
    public int getDefaultWindowWidth() { return defaultWindowWidth; }
    public int getDefaultWindowHeight() { return defaultWindowHeight; }
    public String getDefaultJvmArgs() { return defaultJvmArgs == null ? "" : defaultJvmArgs; }
    public int getLauncherWidth() { return launcherWidth; }
    public int getLauncherHeight() { return launcherHeight; }
    public boolean isCloseToBackground() { return closeToBackground; }
    public boolean isMinimizeOnLaunch() { return minimizeOnLaunch; }
    public int getMaxConcurrentDownloads() { return maxConcurrentDownloads > 0 ? maxConcurrentDownloads : DownloadManager.defaultLimits()[0]; }
    public int getMaxConcurrentWrites() { return maxConcurrentWrites > 0 ? maxConcurrentWrites : DownloadManager.defaultLimits()[1]; }
}
