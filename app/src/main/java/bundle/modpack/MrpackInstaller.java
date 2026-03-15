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

public class MrpackInstaller {

    public void install(Path mrpackFile, Path targetDir, ProgressCallback callback) throws IOException {
        JsonObject index = readJsonFromZip(mrpackFile, "modrinth.index.json");
        if (index == null) throw new IOException("Invalid .mrpack: missing modrinth.index.json");

        JsonArray files = index.getAsJsonArray("files");
        List<FileEntry> entries = new ArrayList<>();
        for (JsonElement el : files) {
            JsonObject f = el.getAsJsonObject();
            String path = f.get("path").getAsString();
            JsonArray downloads = f.getAsJsonArray("downloads");
            if (downloads != null && downloads.size() > 0) {
                entries.add(new FileEntry(path, downloads.get(0).getAsString()));
            }
        }

        long total = entries.size();
        if (callback != null) callback.onPhaseStart("Descarga", total);

        long completed = 0;
        for (FileEntry entry : entries) {
            Path dest = targetDir.resolve(entry.path);
            Files.createDirectories(dest.getParent());
            try {
                byte[] data = HttpConnectionPool.getInstance().getBytes(entry.url);
                Files.write(dest, data, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
            } catch (Exception e) {
                System.err.println("[MRPACK] Error descargando " + entry.path + ": " + e.getMessage());
            }
            completed++;
            if (callback != null) callback.onProgress(completed, total, 0, entry.path, "Descarga");
        }

        if (callback != null) callback.onPhaseComplete("Descarga");

        extractOverrides(mrpackFile, targetDir, "overrides", callback);
    }

    private void extractOverrides(Path packFile, Path targetDir, String prefix, ProgressCallback callback) throws IOException {
        String prefixSlash = prefix + "/";
        try (ZipFile zip = new ZipFile(packFile.toFile(), StandardCharsets.UTF_8)) {
            List<? extends ZipEntry> overrides = zip.stream()
                    .filter(e -> e.getName().startsWith(prefixSlash) && !e.isDirectory())
                    .toList();

            if (overrides.isEmpty()) return;

            long total = overrides.size();
            if (callback != null) callback.onPhaseStart("Extracción", total);

            long completed = 0;
            for (ZipEntry entry : overrides) {
                String relative = entry.getName().substring(prefixSlash.length());
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

    private static class FileEntry {
        final String path;
        final String url;
        FileEntry(String path, String url) { this.path = path; this.url = url; }
    }
}