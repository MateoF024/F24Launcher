package bundle.settings;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.annotations.Expose;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;

public class AppSettings {

    private static final String SETTINGS_FILE = "installer_settings.json";
    private static final Path SETTINGS_PATH = getSettingsPath();
    private static AppSettings instance;

    @Expose private boolean darkMode = true;
    @Expose private String lastGameDir = "";
    @Expose private boolean autoCleanup = true;
    @Expose private boolean showBetaVersions = false;
    @Expose private Map<String, Boolean> folderSelections = new LinkedHashMap<>();
    @Expose private String selectedLoaderType = "Fabric";
    @Expose private boolean preserveUserSettings = true;

    public enum FolderType {
        MODS("mods", "Mods"),
        CONFIG("config", "Configs"),
        RESOURCEPACKS("resourcepacks", "Resourcepacks"),
        SHADERPACKS("shaderpacks", "Shaderpacks"),
        SCHEMATICS("schematics", "Schematics"),
        KUBEJS("kubejs", "KubeJS"),
        SCRIPTS("scripts", "Scripts"),
        DEFAULTCONFIGS("defaultconfigs", "Default Configs");

        private final String folderName;
        private final String displayName;

        FolderType(String folderName, String displayName) {
            this.folderName = folderName;
            this.displayName = displayName;
        }

        public String getFolderName() { return folderName; }
        public String getDisplayName() { return displayName; }

        @Override
        public String toString() { return displayName; }
    }

    private AppSettings() {
        initializeDefaultFolderSelections();
    }

    public static synchronized AppSettings getInstance() {
        if (instance == null) {
            instance = loadSettings();
        }
        return instance;
    }

    private static Path getSettingsPath() {
        String userHome = System.getProperty("user.home");
        Path appDir;

        String os = System.getProperty("os.name").toLowerCase();
        if (os.contains("win")) {
            appDir = Paths.get(userHome, "AppData", "Roaming", "MateoF24-ModpackInstaller");
        } else if (os.contains("mac")) {
            appDir = Paths.get(userHome, "Library", "Application Support", "MateoF24-ModpackInstaller");
        } else {
            appDir = Paths.get(userHome, ".mateof24-modpack-installer");
        }

        try {
            Files.createDirectories(appDir);
        } catch (Exception e) {
            return Paths.get(System.getProperty("java.io.tmpdir"), SETTINGS_FILE);
        }

        return appDir.resolve(SETTINGS_FILE);
    }

    private void initializeDefaultFolderSelections() {
        for (FolderType folder : FolderType.values()) {
            folderSelections.put(folder.getFolderName(), true);
        }
    }

    private static AppSettings loadSettings() {
        if (Files.exists(SETTINGS_PATH)) {
            try (Reader reader = Files.newBufferedReader(SETTINGS_PATH)) {
                Gson gson = new GsonBuilder().excludeFieldsWithoutExposeAnnotation().create();
                AppSettings settings = gson.fromJson(reader, AppSettings.class);

                if (settings != null) {
                    settings.validateSettings();
                    return settings;
                }
            } catch (Exception e) {
                System.err.println("Error cargando configuraciones: " + e.getMessage());
            }
        }

        AppSettings defaultSettings = new AppSettings();
        defaultSettings.saveSettings();
        return defaultSettings;
    }

    private void validateSettings() {
        if (folderSelections == null) {
            folderSelections = new LinkedHashMap<>();
        }

        for (FolderType folder : FolderType.values()) {
            folderSelections.putIfAbsent(folder.getFolderName(), true);
        }

        if (selectedLoaderType == null || selectedLoaderType.isEmpty()) {
            selectedLoaderType = "Fabric";
        }
    }

    public synchronized void saveSettings() {
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

    private void saveIfEnabled() {
        if (preserveUserSettings) {
            saveSettings();
        }
    }

    public boolean isDarkMode() {
        return darkMode;
    }

    public void setDarkMode(boolean darkMode) {
        if (this.darkMode != darkMode) {
            this.darkMode = darkMode;
            saveIfEnabled();
        }
    }

    public String getLastGameDir() {
        return lastGameDir;
    }

    public void setLastGameDir(String lastGameDir) {
        if (!Objects.equals(this.lastGameDir, lastGameDir)) {
            this.lastGameDir = lastGameDir != null ? lastGameDir : "";
            saveIfEnabled();
        }
    }

    public boolean isAutoCleanup() {
        return autoCleanup;
    }

    public void setAutoCleanup(boolean autoCleanup) {
        if (this.autoCleanup != autoCleanup) {
            this.autoCleanup = autoCleanup;
            saveIfEnabled();
        }
    }

    public boolean isShowBetaVersions() {
        return showBetaVersions;
    }

    public void setShowBetaVersions(boolean showBetaVersions) {
        if (this.showBetaVersions != showBetaVersions) {
            this.showBetaVersions = showBetaVersions;
            saveIfEnabled();
        }
    }

    public String getSelectedLoaderType() {
        return selectedLoaderType;
    }

    public void setSelectedLoaderType(String selectedLoaderType) {
        if (!Objects.equals(this.selectedLoaderType, selectedLoaderType)) {
            this.selectedLoaderType = selectedLoaderType != null ? selectedLoaderType : "Fabric";
            saveIfEnabled();
        }
    }

    public boolean isPreserveUserSettings() {
        return preserveUserSettings;
    }

    public void setPreserveUserSettings(boolean preserveUserSettings) {
        if (this.preserveUserSettings != preserveUserSettings) {
            this.preserveUserSettings = preserveUserSettings;

            if (preserveUserSettings) {
                saveSettings();
            }
        }
    }

    public boolean isFolderSelected(String folderName) {
        return folderSelections.getOrDefault(folderName, true);
    }

    public void setFolderSelected(String folderName, boolean selected) {
        Boolean oldValue = folderSelections.get(folderName);
        if (oldValue == null || oldValue != selected) {
            folderSelections.put(folderName, selected);
            saveIfEnabled();
        }
    }

    public void toggleFolder(String folderName) {
        boolean currentState = isFolderSelected(folderName);
        setFolderSelected(folderName, !currentState);
    }

    public Map<String, Boolean> getAllFolderSelections() {
        return new HashMap<>(folderSelections);
    }

    public List<String> getFoldersToInstall() {
        List<String> result = new ArrayList<>();
        for (Map.Entry<String, Boolean> entry : folderSelections.entrySet()) {
            if (entry.getValue()) {
                result.add(entry.getKey());
            }
        }
        return result;
    }

    public List<String> getFoldersToPreserve() {
        List<String> result = new ArrayList<>();
        for (Map.Entry<String, Boolean> entry : folderSelections.entrySet()) {
            if (!entry.getValue()) {
                result.add(entry.getKey());
            }
        }
        return result;
    }

    public void resetToDefaults() {
        this.darkMode = true;
        this.lastGameDir = "";
        this.autoCleanup = true;
        this.showBetaVersions = false;
        this.selectedLoaderType = "Fabric";
        this.preserveUserSettings = true;
        initializeDefaultFolderSelections();
        saveSettings();
    }
}