package bundle.loader;

import bundle.util.HttpConnectionPool;


import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.Collectors;

public class LoaderManager {

    private static final ExecutorService EXECUTOR = Executors.newCachedThreadPool();
    private final Path gameDir;

    public enum LoaderType {
        FABRIC("Fabric", "https://meta.fabricmc.net/v2/versions"),
        FORGE("Forge", "https://files.minecraftforge.net/net/minecraftforge/forge/promotions_slim.json"),
        NEOFORGE("NeoForge", "https://maven.neoforged.net/api/maven/versions/releases/net/neoforged/neoforge");

        public final String displayName;
        public final String apiUrl;

        LoaderType(String displayName, String apiUrl) {
            this.displayName = displayName;
            this.apiUrl = apiUrl;
        }
    }

    public static class MinecraftVersion {
        public final String id;
        public final String type;
        public final boolean stable;

        public MinecraftVersion(String id, String type, boolean stable) {
            this.id = id;
            this.type = type;
            this.stable = stable;
        }

        @Override
        public String toString() {
            return id;
        }
    }

    public static class LoaderVersion {
        public final String version;
        public final String minecraftVersion;
        public final LoaderType loaderType;
        public final boolean stable;
        public final String downloadUrl;

        public LoaderVersion(String version, String minecraftVersion, LoaderType loaderType, boolean stable, String downloadUrl) {
            this.version = version;
            this.minecraftVersion = minecraftVersion;
            this.loaderType = loaderType;
            this.stable = stable;
            this.downloadUrl = downloadUrl;
        }

        @Override
        public String toString() {
            return version;
        }
    }

    public static class InstallResult {
        public final boolean success;
        public final String message;

        public InstallResult(boolean success, String message) {
            this.success = success;
            this.message = message;
        }
    }

    public interface InstallProgressCallback {
        void onStart(String message);
        void onProgress(int percentage, String message);
        void onComplete(boolean success, String message);
    }

    public LoaderManager(Path gameDir) {
        this.gameDir = gameDir;
    }

    public CompletableFuture<List<MinecraftVersion>> getMinecraftVersions() {
        return CompletableFuture.supplyAsync(() -> {
            try {
                System.out.println("[API] Conectando a Mojang para obtener versiones de Minecraft...");

                String jsonResponse = HttpConnectionPool.getInstance().get("https://piston-meta.mojang.com/mc/game/version_manifest.json");
                JsonObject manifest = new Gson().fromJson(jsonResponse, JsonObject.class);
                JsonArray versions = manifest.getAsJsonArray("versions");

                List<MinecraftVersion> result = new ArrayList<>();

                for (JsonElement element : versions) {
                    JsonObject versionObj = element.getAsJsonObject();
                    String id = versionObj.get("id").getAsString();
                    String type = versionObj.get("type").getAsString();
                    boolean stable = "release".equals(type);

                    result.add(new MinecraftVersion(id, type, stable));
                }

                System.out.println("[API] ✓ Obtenidas " + result.size() + " versiones de Minecraft desde Mojang");
                return result;

            } catch (Exception e) {
                System.err.println("✗ Error conectando con Mojang: " + e.getMessage());
                e.printStackTrace();
                throw new RuntimeException("No se pudo conectar con Mojang para obtener versiones de Minecraft", e);
            }
        }, EXECUTOR);
    }

    public CompletableFuture<List<LoaderVersion>> getLoaderVersions(LoaderType loaderType, String minecraftVersion) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                System.out.println("[API] Obteniendo versiones REALES de " + loaderType.displayName + " para MC " + minecraftVersion);

                switch (loaderType) {
                    case FABRIC:
                        return getFabricVersionsRealTime(minecraftVersion);
                    case FORGE:
                        return getForgeVersionsRealTime(minecraftVersion);
                    case NEOFORGE:
                        return getNeoForgeVersionsRealTime(minecraftVersion);
                    default:
                        return new ArrayList<>();
                }

            } catch (Exception e) {
                System.err.println("✗ Error conectando con " + loaderType.displayName + ": " + e.getMessage());
                e.printStackTrace();
                return new ArrayList<>();
            }
        }, EXECUTOR);
    }

    private List<LoaderVersion> getFabricVersionsRealTime(String minecraftVersion) throws Exception {
        System.out.println("[API] Consultando API de Fabric en tiempo real...");

        String gameResponse = HttpConnectionPool.getInstance().get("https://meta.fabricmc.net/v2/versions/game");
        JsonArray gameVersions = new Gson().fromJson(gameResponse, JsonArray.class);

        boolean mcVersionSupported = false;
        for (JsonElement element : gameVersions) {
            JsonObject versionObj = element.getAsJsonObject();
            if (minecraftVersion.equals(versionObj.get("version").getAsString())) {
                mcVersionSupported = true;
                break;
            }
        }

        if (!mcVersionSupported) {
            System.out.println("[API] ⚠ Fabric no soporta MC " + minecraftVersion);
            return Collections.emptyList();
        }

        String loaderResponse = HttpConnectionPool.getInstance().get("https://meta.fabricmc.net/v2/versions/loader");
        JsonArray loaderVersions = new Gson().fromJson(loaderResponse, JsonArray.class);

        List<LoaderVersion> versions = new ArrayList<>();

        for (JsonElement element : loaderVersions) {
            JsonObject versionObj = element.getAsJsonObject();
            String version = versionObj.get("version").getAsString();
            boolean stable = versionObj.get("stable").getAsBoolean();

            String profileUrl = String.format("https://meta.fabricmc.net/v2/versions/loader/%s/%s/profile/json",
                    minecraftVersion, version);

            versions.add(new LoaderVersion(version, minecraftVersion, LoaderType.FABRIC, stable, profileUrl));

            if (versions.size() >= 20) break;
        }

        System.out.println("[API] ✓ Fabric: " + versions.size() + " versiones obtenidas en tiempo real");
        return versions;
    }

    private List<LoaderVersion> getForgeVersionsRealTime(String minecraftVersion) throws Exception {
        System.out.println("[API] Consultando API oficial de Forge en tiempo real...");

        String promoResponse = HttpConnectionPool.getInstance().get("https://files.minecraftforge.net/net/minecraftforge/forge/promotions_slim.json");
        JsonObject promotions = new Gson().fromJson(promoResponse, JsonObject.class);
        JsonObject promos = promotions.getAsJsonObject("promos");

        List<LoaderVersion> versions = new ArrayList<>();

        String recommendedKey = minecraftVersion + "-recommended";
        String latestKey = minecraftVersion + "-latest";

        if (promos.has(recommendedKey)) {
            String recommended = promos.get(recommendedKey).getAsString();
            String fullVersion = minecraftVersion + "-" + recommended;
            String installerUrl = String.format(
                    "https://maven.minecraftforge.net/net/minecraftforge/forge/%s/forge-%s-installer.jar",
                    fullVersion, fullVersion);

            versions.add(new LoaderVersion(recommended, minecraftVersion, LoaderType.FORGE, true, installerUrl));
        }

        if (promos.has(latestKey)) {
            String latest = promos.get(latestKey).getAsString();
            if (versions.stream().noneMatch(v -> v.version.equals(latest))) {
                String fullVersion = minecraftVersion + "-" + latest;
                String installerUrl = String.format(
                        "https://maven.minecraftforge.net/net/minecraftforge/forge/%s/forge-%s-installer.jar",
                        fullVersion, fullVersion);

                versions.add(new LoaderVersion(latest, minecraftVersion, LoaderType.FORGE, false, installerUrl));
            }
        }

        System.out.println("[API] ✓ Forge: " + versions.size() + " versiones obtenidas en tiempo real");
        return versions;
    }

    private List<LoaderVersion> getNeoForgeVersionsRealTime(String minecraftVersion) throws Exception {
        System.out.println("[API] Consultando API oficial de NeoForge en tiempo real...");

        String apiResponse = HttpConnectionPool.getInstance().get("https://maven.neoforged.net/api/maven/versions/releases/net/neoforged/neoforge");
        JsonObject response = new Gson().fromJson(apiResponse, JsonObject.class);
        JsonArray versionArray = response.getAsJsonArray("versions");

        List<LoaderVersion> versions = new ArrayList<>();

        if (versionArray != null) {
            String neoforgePrefix = convertMcVersionToNeoForgePrefix(minecraftVersion);
            System.out.println("[API] Buscando versiones NeoForge con prefijo: " + neoforgePrefix + " para MC " + minecraftVersion);

            List<String> allVersions = new ArrayList<>();
            for (JsonElement element : versionArray) {
                allVersions.add(element.getAsString());
            }

            List<String> matchingVersions = allVersions.stream()
                    .filter(version -> version.startsWith(neoforgePrefix))
                    .sorted((v1, v2) -> compareNeoForgeVersions(v2, v1))
                    .limit(20)
                    .collect(Collectors.toList());

            for (String version : matchingVersions) {
                String installerUrl = String.format(
                        "https://maven.neoforged.net/releases/net/neoforged/neoforge/%s/neoforge-%s-installer.jar",
                        version, version);

                versions.add(new LoaderVersion(version, minecraftVersion, LoaderType.NEOFORGE, true, installerUrl));
            }

            System.out.println("[API] ✓ NeoForge: " + versions.size() + " versiones obtenidas en tiempo real para " + minecraftVersion);

            if (versions.isEmpty()) {
                System.out.println("[API] ⚠ No se encontraron versiones de NeoForge para " + minecraftVersion);
                System.out.println("[API] Versiones disponibles (muestra):");
                allVersions.stream().limit(10).forEach(v -> System.out.println("  - " + v));
            }
        } else {
            System.err.println("[API] ✗ Respuesta JSON de NeoForge no contiene array 'versions'");
        }

        return versions;
    }

    private String convertMcVersionToNeoForgePrefix(String mcVersion) {
        String[] parts = mcVersion.split("\\.");
        if (parts.length >= 3) {
            return parts[1] + "." + parts[2];
        } else if (parts.length == 2) {
            return parts[1] + ".0";
        }
        return mcVersion;
    }

    private int compareNeoForgeVersions(String v1, String v2) {
        try {
            String[] parts1 = v1.split("\\.");
            String[] parts2 = v2.split("\\.");

            for (int i = 0; i < Math.max(parts1.length, parts2.length); i++) {
                int part1 = i < parts1.length ? Integer.parseInt(parts1[i]) : 0;
                int part2 = i < parts2.length ? Integer.parseInt(parts2[i]) : 0;

                if (part1 != part2) {
                    return Integer.compare(part1, part2);
                }
            }
            return 0;
        } catch (Exception e) {
            return v1.compareTo(v2);
        }
    }

    public CompletableFuture<InstallResult> installLoader(LoaderVersion loaderVersion,
                                                          InstallProgressCallback callback) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                Path versionsDir = gameDir.resolve("versions");
                Files.createDirectories(versionsDir);

                if (callback != null) {
                    callback.onStart("Iniciando instalación de " + loaderVersion.loaderType.displayName + " " + loaderVersion.version);
                }

                switch (loaderVersion.loaderType) {
                    case FABRIC:
                        return installFabricLoader(loaderVersion, versionsDir, callback);
                    case FORGE:
                    case NEOFORGE:
                        return installForgeBasedLoader(loaderVersion, versionsDir, callback);
                    default:
                        return new InstallResult(false, "Tipo de loader no soportado");
                }

            } catch (Exception e) {
                System.err.println("✗ Error durante la instalación: " + e.getMessage());
                e.printStackTrace();

                String message = "Error instalando " + loaderVersion.loaderType.displayName + ": " + e.getMessage();
                if (callback != null) {
                    callback.onComplete(false, message);
                }
                return new InstallResult(false, message);
            }
        }, EXECUTOR);
    }

    private InstallResult installFabricLoader(LoaderVersion loaderVersion, Path versionsDir,
                                              InstallProgressCallback callback) throws Exception {
        if (callback != null) {
            callback.onProgress(25, "Descargando perfil de Fabric...");
        }

        String versionName = "fabric-loader-" + loaderVersion.version + "-" + loaderVersion.minecraftVersion;
        Path versionDir = versionsDir.resolve(versionName);
        Files.createDirectories(versionDir);

        byte[] profileData = HttpConnectionPool.getInstance().getBytes(loaderVersion.downloadUrl);
        Path jsonFile = versionDir.resolve(versionName + ".json");

        if (callback != null) {
            callback.onProgress(75, "Creando perfil de launcher...");
        }

        try (ByteArrayInputStream in = new ByteArrayInputStream(profileData)) {
            Files.copy(in, jsonFile, StandardCopyOption.REPLACE_EXISTING);
        }

        if (callback != null) {
            callback.onProgress(100, "Instalación de Fabric completada");
        }

        String message = "Fabric Loader " + loaderVersion.version + " para Minecraft " +
                loaderVersion.minecraftVersion + " instalado correctamente. Perfil creado: " + versionName;

        if (callback != null) {
            callback.onComplete(true, message);
        }

        return new InstallResult(true, message);
    }

    private InstallResult installForgeBasedLoader(LoaderVersion loaderVersion, Path versionsDir,
                                                  InstallProgressCallback callback) throws Exception {
        if (callback != null) {
            callback.onProgress(10, "Descargando instalador oficial...");
        }

        byte[] installerData = HttpConnectionPool.getInstance().getBytes(loaderVersion.downloadUrl);
        Path tempInstaller = Files.createTempFile(loaderVersion.loaderType.name().toLowerCase() + "-installer", ".jar");

        try {
            try (ByteArrayInputStream in = new ByteArrayInputStream(installerData)) {
                Files.copy(in, tempInstaller, StandardCopyOption.REPLACE_EXISTING);
            }

            if (callback != null) {
                callback.onProgress(30, "Preparando instalación...");
            }

            String javaCommand = getJavaCommand();
            if (javaCommand == null) {
                throw new RuntimeException("Java no encontrado. Se requiere Java para instalar " + loaderVersion.loaderType.displayName);
            }

            if (callback != null) {
                callback.onProgress(40, "Ejecutando instalador oficial...");
            }

            ProcessBuilder pb = new ProcessBuilder(
                    javaCommand,
                    "-jar", tempInstaller.toString(),
                    "--installClient",
                    gameDir.toString()
            );

            pb.directory(gameDir.toFile());
            pb.redirectErrorStream(true);

            System.out.println("[INSTALLER] Ejecutando: " + String.join(" ", pb.command()));
            System.out.println("[INSTALLER] Directorio: " + pb.directory());

            Process process = pb.start();

            StringBuilder output = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    System.out.println("[INSTALLER] " + line);
                    output.append(line).append("\n");

                    if (callback != null) {
                        if (line.contains("Downloading") || line.contains("downloading") || line.contains("GET")) {
                            callback.onProgress(60, "Descargando librerías...");
                        } else if (line.contains("Installing") || line.contains("installing") || line.contains("Writing")) {
                            callback.onProgress(80, "Instalando componentes...");
                        } else if (line.contains("Finished") || line.contains("finished") || line.contains("Success") || line.contains("Complete")) {
                            callback.onProgress(95, "Finalizando instalación...");
                        }
                    }
                }
            }

            int exitCode = process.waitFor();

            if (callback != null) {
                callback.onProgress(98, "Verificando instalación...");
            }

            if (exitCode == 0) {
                boolean installed = verifyLoaderInstallation(loaderVersion, versionsDir);

                String message;
                if (installed) {
                    message = loaderVersion.loaderType.displayName + " " + loaderVersion.version +
                            " para Minecraft " + loaderVersion.minecraftVersion +
                            " instalado correctamente con todas sus librerías.";

                    if (callback != null) {
                        callback.onComplete(true, message);
                    }
                    return new InstallResult(true, message);
                } else {
                    if (loaderVersion.loaderType == LoaderType.NEOFORGE) {
                        message = loaderVersion.loaderType.displayName + " " + loaderVersion.version +
                                " para Minecraft " + loaderVersion.minecraftVersion +
                                " instalado correctamente. Verifica el perfil en tu launcher.";

                        if (callback != null) {
                            callback.onComplete(true, message);
                        }
                        return new InstallResult(true, message);
                    } else {
                        message = "La instalación pareció completarse pero no se pudo verificar automáticamente. " +
                                "Revisa tu launcher para confirmar que el perfil fue creado.";

                        if (callback != null) {
                            callback.onComplete(false, message);
                        }
                        return new InstallResult(false, message);
                    }
                }
            } else {
                String message = "El instalador falló con código de salida: " + exitCode + "\n\n" +
                        "Salida del instalador:\n" + output.toString();

                System.err.println("[INSTALLER] " + message);

                if (callback != null) {
                    callback.onComplete(false, "Error en la instalación (código " + exitCode + ")");
                }

                return new InstallResult(false, message);
            }

        } finally {
            try {
                Files.deleteIfExists(tempInstaller);
            } catch (Exception e) {
                System.err.println("No se pudo eliminar archivo temporal: " + e.getMessage());
            }
        }
    }

    private String getJavaCommand() {
        String[] javaCommands = {"java", "java.exe"};

        for (String cmd : javaCommands) {
            try {
                ProcessBuilder pb = new ProcessBuilder(cmd, "-version");
                Process process = pb.start();
                int exitCode = process.waitFor();
                if (exitCode == 0) {
                    return cmd;
                }
            } catch (Exception e) {

            }
        }

        String javaHome = System.getProperty("java.home");
        if (javaHome != null) {
            Path javaBin = Paths.get(javaHome, "bin", "java");
            if (Files.exists(javaBin)) {
                return javaBin.toString();
            }

            javaBin = Paths.get(javaHome, "bin", "java.exe");
            if (Files.exists(javaBin)) {
                return javaBin.toString();
            }
        }

        return null;
    }

    private boolean verifyLoaderInstallation(LoaderVersion loaderVersion, Path versionsDir) {
        try {
            String loaderName = loaderVersion.loaderType.name().toLowerCase();
            String mcVersion = loaderVersion.minecraftVersion;
            String loaderVersionNum = loaderVersion.version;

            System.out.println("[VERIFY] Verificando instalación de " + loaderName + " " + loaderVersionNum + " para MC " + mcVersion);

            try (DirectoryStream<Path> stream = Files.newDirectoryStream(versionsDir)) {
                for (Path versionDir : stream) {
                    if (Files.isDirectory(versionDir)) {
                        String versionName = versionDir.getFileName().toString();
                        String versionNameLower = versionName.toLowerCase();

                        System.out.println("[VERIFY] Revisando: " + versionName);

                        boolean matches = false;

                        switch (loaderVersion.loaderType) {
                            case FABRIC:
                                matches = versionNameLower.contains("fabric") &&
                                        versionNameLower.contains(loaderVersionNum) &&
                                        versionNameLower.contains(mcVersion);
                                break;

                            case FORGE:
                                matches = versionNameLower.contains("forge") &&
                                        (versionNameLower.contains(mcVersion) || versionNameLower.contains(loaderVersionNum));
                                break;

                            case NEOFORGE:
                                matches = versionNameLower.contains("neoforge") &&
                                        (versionNameLower.contains(loaderVersionNum) ||
                                                versionNameLower.contains(mcVersion));
                                break;
                        }

                        if (matches) {
                            Path jsonFile = versionDir.resolve(versionName + ".json");
                            if (Files.exists(jsonFile)) {
                                System.out.println("[VERIFY] ✓ Instalación verificada: " + versionName);
                                return true;
                            } else {
                                System.out.println("[VERIFY] Directorio encontrado pero sin JSON: " + versionName);
                            }
                        }
                    }
                }
            }

            System.out.println("[VERIFY] ⚠ No se pudo verificar automáticamente la instalación");

            System.out.println("[VERIFY] Contenido del directorio versions:");
            try (DirectoryStream<Path> stream = Files.newDirectoryStream(versionsDir)) {
                for (Path dir : stream) {
                    if (Files.isDirectory(dir)) {
                        System.out.println("[VERIFY] - " + dir.getFileName().toString());
                    }
                }
            }

        } catch (Exception e) {
            System.err.println("[VERIFY] Error verificando instalación: " + e.getMessage());
            e.printStackTrace();
        }

        return false;
    }

    public static void shutdown() {
        EXECUTOR.shutdown();
    }
}