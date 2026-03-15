package bundle.instance;

import com.google.gson.*;

import java.io.*;
import java.nio.file.*;
import java.util.*;

public class InstanceManager {

    private static final String INSTANCES_FILE = "instances.json";
    private final Path instancesFile;
    private List<GameInstance> instances;

    public InstanceManager() {
        this.instancesFile = getAppDir().resolve(INSTANCES_FILE);
        this.instances = new ArrayList<>();
        load();
    }

    private static Path getAppDir() {
        String home = System.getProperty("user.home");
        String os = System.getProperty("os.name").toLowerCase();
        Path dir;
        if (os.contains("win"))       dir = Paths.get(home, "AppData", "Roaming", "MateoF24-ModpackInstaller");
        else if (os.contains("mac"))  dir = Paths.get(home, "Library", "Application Support", "MateoF24-ModpackInstaller");
        else                          dir = Paths.get(home, ".mateof24-modpack-installer");
        try { Files.createDirectories(dir); } catch (Exception ignored) {}
        return dir;
    }

    private void load() {
        if (!Files.exists(instancesFile)) return;
        try (Reader r = Files.newBufferedReader(instancesFile)) {
            JsonObject root = new Gson().fromJson(r, JsonObject.class);
            if (root != null && root.has("instances")) {
                Gson gson = new GsonBuilder().excludeFieldsWithoutExposeAnnotation().create();
                for (JsonElement el : root.getAsJsonArray("instances")) {
                    GameInstance inst = gson.fromJson(el, GameInstance.class);
                    if (inst != null && inst.path != null) instances.add(inst);
                }
            }
        } catch (Exception ignored) {}
    }

    public synchronized void save() {
        try {
            Gson gson = new GsonBuilder().excludeFieldsWithoutExposeAnnotation().setPrettyPrinting().create();
            JsonObject root = new JsonObject();
            JsonArray arr = new JsonArray();
            for (GameInstance i : instances) arr.add(gson.toJsonTree(i));
            root.add("instances", arr);
            try (Writer w = Files.newBufferedWriter(instancesFile)) { gson.toJson(root, w); }
        } catch (Exception ignored) {}
    }

    public List<GameInstance> getInstances() {
        return Collections.unmodifiableList(instances);
    }

    public boolean addInstance(Path dir) {
        if (!Files.isDirectory(dir)) return false;
        Path modsDir = dir.resolve("mods");
        if (!Files.exists(modsDir)) {
            try { Files.createDirectories(modsDir); } catch (Exception e) { return false; }
        }
        String path = dir.toString();
        if (instances.stream().anyMatch(i -> i.path.equals(path))) return false;

        InstanceDetector.DetectionResult detected = InstanceDetector.detect(dir);
        instances.add(new GameInstance(
                dir.getFileName().toString(),
                path,
                detected.minecraftVersion,
                detected.loader,
                detected.loaderVersion,
                detected.launcherName
        ));
        save();
        return true;
    }

    public void removeInstance(String path) {
        instances.removeIf(i -> i.path.equals(path));
        save();
    }
}