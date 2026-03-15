package bundle.modpack;

import bundle.download.ProgressCallback;
import bundle.util.HttpConnectionPool;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

public class CurseForgeInstaller {

    private static final String CURSE_TOOLS_API = "https://api.curse.tools/v1/cf/mods/%d/files/%d";

    public void install(Path packFile, Path targetDir, ProgressCallback callback) throws IOException {
        JsonObject manifest = readJsonFromZip(packFile, "manifest.json");
        if (manifest == null) throw new IOException("Invalid CurseForge pack: missing manifest.json");

        JsonArray files = manifest.getAsJsonArray("files");
        String overridesDir = manifest.has("overrides") ? manifest.get("overrides").getAsString() : "overrides";

        List<ModFile> modFiles = new ArrayList<>();
        for (JsonElement el : files) {
            JsonObject f = el.getAsJsonObject();
            modFiles.add(new ModFile(f.get("projectID").getAsInt(), f.get("fileID").getAsInt()));
        }

        long total = modFiles.size();
        if (callback != null) callback.onPhaseStart("Descarga", total);

        Path modsDir = targetDir.resolve("mods");
        Files.createDirectories(modsDir);

        long completed = 0;
        for (ModFile mod : modFiles) {
            try {
                String url = resolveDownloadUrl(mod.projectId, mod.fileId);
                if (url != null && !url.isEmpty()) {
                    String fileName = url.substring(url.lastIndexOf('/') + 1);
                    byte[] data = HttpConnectionPool.getInstance().getBytes(url);
                    Files.write(modsDir.resolve(fileName), data, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
                }
            } catch (Exception e) {
                System.err.println("[CURSEFORGE] Error mod " + mod.projectId + "/" + mod.fileId + ": " + e.getMessage());
            }
            completed++;
            if (callback != null) callback.onProgress(completed, total, 0, mod.projectId + "/" + mod.fileId, "Descarga");
        }

        if (callback != null) callback.onPhaseComplete("Descarga");

        extractOverrides(packFile, targetDir, overridesDir, callback);
    }

    private String resolveDownloadUrl(int projectId, int fileId) throws IOException {
        String response = HttpConnectionPool.getInstance().get(String.format(CURSE_TOOLS_API, projectId, fileId));
        JsonObject root = new Gson().fromJson(response, JsonObject.class);
        if (!root.has("data")) return null;
        JsonObject data = root.getAsJsonObject("data");
        if (data.has("downloadUrl") && !data.get("downloadUrl").isJsonNull()) {
            return data.get("downloadUrl").getAsString();
        }
        if (data.has("fileName")) {
            String fileName = data.get("fileName").getAsString();
            return String.format("https://mediafiles.forgecdn.net/files/%d/%d/%s", fileId / 1000, fileId % 1000, fileName);
        }
        return null;
    }

    private void extractOverrides(Path packFile, Path targetDir, String overridesDir, ProgressCallback callback) throws IOException {
        String prefix = overridesDir + "/";
        try (ZipFile zip = new ZipFile(packFile.toFile(), StandardCharsets.UTF_8)) {
            List<? extends ZipEntry> entries = zip.stream()
                    .filter(e -> e.getName().startsWith(prefix) && !e.isDirectory())
                    .toList();

            if (entries.isEmpty()) return;

            long total = entries.size();
            if (callback != null) callback.onPhaseStart("Extracción", total);

            long completed = 0;
            for (ZipEntry entry : entries) {
                String relative = entry.getName().substring(prefix.length());
                if (relative.isEmpty()) continue;
                Path dest = targetDir.resolve(relative);
                Files.createDirectories(dest.getParent());
                try (InputStream is = zip.getInputStream(entry)) {
                    Files.write(dest, is.readAllBytes(), StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
                }
                completed++;
                if (callback != null) callback.onProgress(completed, total, 0, relative, "Extracción");
            }

            if (callback != null) callback.onPhaseComplete("Extracción");
        }
    }

    private JsonObject readJsonFromZip(Path zipPath, String entryName) throws IOException {
        try (ZipFile zip = new ZipFile(zipPath.toFile(), StandardCharsets.UTF_8)) {
            ZipEntry entry = zip.getEntry(entryName);
            if (entry == null) return null;
            try (InputStream is = zip.getInputStream(entry)) {
                return new Gson().fromJson(new String(is.readAllBytes(), StandardCharsets.UTF_8), JsonObject.class);
            }
        }
    }

    private static class ModFile {
        final int projectId;
        final int fileId;
        ModFile(int projectId, int fileId) { this.projectId = projectId; this.fileId = fileId; }
    }
}