package bundle.instance;

import com.google.gson.*;

import java.io.Reader;
import java.nio.file.*;

public class InstanceDetector {

    public static class DetectionResult {
        public String minecraftVersion = "";
        public String loader = "";
        public String loaderVersion = "";
        public String launcherName = "Desconocido";
    }

    public static DetectionResult detect(Path dir) {
        DetectionResult r = new DetectionResult();

        if (tryModrinth(dir, r)) return r;
        if (tryCurseForge(dir, r)) return r;
        if (tryMultiMCPrism(dir, r)) return r;
        if (tryATLauncher(dir, r)) return r;
        if (tryGDLauncher(dir, r)) return r;

        return r;
    }

    private static boolean tryModrinth(Path dir, DetectionResult r) {
        Path f = dir.resolve("profile.json");
        if (!Files.exists(f)) return false;
        try (Reader reader = Files.newBufferedReader(f)) {
            JsonObject obj = new Gson().fromJson(reader, JsonObject.class);
            if (obj == null) return false;
            r.launcherName = "Modrinth";
            r.minecraftVersion = str(obj, "game_version");
            if (obj.has("loader") && !obj.get("loader").isJsonNull()) {
                r.loader = obj.get("loader").getAsString();
            } else {
                r.loader = "vanilla";
            }
            r.loaderVersion = str(obj, "loader_version");
            return true;
        } catch (Exception e) { return false; }
    }

    private static boolean tryCurseForge(Path dir, DetectionResult r) {
        Path f = dir.resolve("minecraftinstance.json");
        if (!Files.exists(f)) return false;
        try (Reader reader = Files.newBufferedReader(f)) {
            JsonObject obj = new Gson().fromJson(reader, JsonObject.class);
            if (obj == null) return false;
            r.launcherName = "CurseForge";
            r.minecraftVersion = str(obj, "gameVersion");
            if (obj.has("baseModLoader") && !obj.get("baseModLoader").isJsonNull()) {
                JsonObject ml = obj.getAsJsonObject("baseModLoader");
                int type = ml.has("type") ? ml.get("type").getAsInt() : 0;
                r.loader = loaderFromCurseType(type);
                if (ml.has("forgeVersion") && !ml.get("forgeVersion").isJsonNull()) {
                    r.loaderVersion = ml.get("forgeVersion").getAsString();
                } else if (ml.has("name") && !ml.get("name").isJsonNull()) {
                    r.loaderVersion = extractVersionFromName(ml.get("name").getAsString());
                }
            } else {
                r.loader = "vanilla";
            }
            return true;
        } catch (Exception e) { return false; }
    }

    private static boolean tryMultiMCPrism(Path dir, DetectionResult r) {
        Path f = dir.resolve("mmc-pack.json");
        if (!Files.exists(f)) return false;
        try (Reader reader = Files.newBufferedReader(f)) {
            JsonObject obj = new Gson().fromJson(reader, JsonObject.class);
            if (obj == null || !obj.has("components")) return false;
            r.launcherName = Files.exists(dir.resolve("prismlauncher.cfg")) ? "Prism" : "MultiMC";
            JsonArray components = obj.getAsJsonArray("components");
            r.loader = "vanilla";
            for (JsonElement el : components) {
                JsonObject comp = el.getAsJsonObject();
                String uid = str(comp, "uid");
                String ver = str(comp, "version");
                switch (uid) {
                    case "net.minecraft" -> r.minecraftVersion = ver;
                    case "net.fabricmc.fabric-loader" -> { r.loader = "fabric"; r.loaderVersion = ver; }
                    case "net.minecraftforge" -> { r.loader = "forge"; r.loaderVersion = ver; }
                    case "net.neoforged" -> { r.loader = "neoforge"; r.loaderVersion = ver; }
                    case "org.quiltmc.quilt-loader" -> { r.loader = "quilt"; r.loaderVersion = ver; }
                }
            }
            return true;
        } catch (Exception e) { return false; }
    }

    private static boolean tryATLauncher(Path dir, DetectionResult r) {
        Path f = dir.resolve("instance.json");
        if (!Files.exists(f)) return false;
        try (Reader reader = Files.newBufferedReader(f)) {
            JsonObject obj = new Gson().fromJson(reader, JsonObject.class);
            if (obj == null || !obj.has("minecraft")) return false;
            r.launcherName = "ATLauncher";
            r.minecraftVersion = str(obj, "minecraft");
            if (obj.has("loader") && !obj.get("loader").isJsonNull()) {
                JsonObject ldr = obj.getAsJsonObject("loader");
                r.loader = str(ldr, "type");
                r.loaderVersion = str(ldr, "version");
            } else {
                r.loader = "vanilla";
            }
            return true;
        } catch (Exception e) { return false; }
    }

    private static boolean tryGDLauncher(Path dir, DetectionResult r) {
        Path f = dir.resolve("config.json");
        if (!Files.exists(f)) return false;
        try (Reader reader = Files.newBufferedReader(f)) {
            JsonObject obj = new Gson().fromJson(reader, JsonObject.class);
            if (obj == null || !obj.has("mcVersion")) return false;
            r.launcherName = "GDLauncher";
            r.minecraftVersion = str(obj, "mcVersion");
            if (obj.has("loader") && !obj.get("loader").isJsonNull()) {
                JsonObject ldr = obj.getAsJsonObject("loader");
                r.loader = str(ldr, "loaderType");
                r.loaderVersion = str(ldr, "loaderVersion");
            } else {
                r.loader = "vanilla";
            }
            return true;
        } catch (Exception e) { return false; }
    }

    private static String loaderFromCurseType(int type) {
        return switch (type) {
            case 1 -> "forge";
            case 4 -> "fabric";
            case 5 -> "quilt";
            case 6 -> "neoforge";
            default -> "vanilla";
        };
    }

    private static String extractVersionFromName(String name) {
        String[] parts = name.split("-");
        for (String p : parts) {
            if (p.matches("\\d+\\.\\d+.*")) return p;
        }
        return name;
    }

    private static String str(JsonObject obj, String key) {
        if (obj == null || !obj.has(key) || obj.get(key).isJsonNull()) return "";
        return obj.get(key).getAsString();
    }
}