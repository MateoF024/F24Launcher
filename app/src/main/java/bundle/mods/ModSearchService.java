package bundle.mods;

import bundle.util.HttpConnectionPool;
import com.google.gson.*;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

public class ModSearchService {

    private static final ExecutorService EXECUTOR = Executors.newCachedThreadPool();
    private static final int PAGE_SIZE = 20;

    public CompletableFuture<Map<String, String>> getCategories(ModSource source) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                return source == ModSource.MODRINTH ? getModrinthCategories() : getCurseForgeCategories();
            } catch (Exception e) { return new LinkedHashMap<>(); }
        }, EXECUTOR);
    }

    private Map<String, String> getModrinthCategories() throws Exception {
        JsonArray arr = new Gson().fromJson(
                HttpConnectionPool.getInstance().get("https://api.modrinth.com/v2/tag/category"), JsonArray.class);
        Map<String, String> cats = new LinkedHashMap<>();
        cats.put("Cualquiera", "");
        if (arr != null) {
            for (JsonElement el : arr) {
                JsonObject obj = el.getAsJsonObject();
                if ("mod".equals(obj.get("project_type").getAsString())) {
                    String name = obj.get("name").getAsString();
                    cats.put(name, name);
                }
            }
        }
        return cats;
    }

    private Map<String, String> getCurseForgeCategories() throws Exception {
        JsonObject root = new Gson().fromJson(
                HttpConnectionPool.getInstance().get("https://api.curse.tools/v1/cf/categories?gameId=432&classId=6"),
                JsonObject.class);
        Map<String, String> cats = new LinkedHashMap<>();
        cats.put("Cualquiera", "");
        if (root != null && root.has("data")) {
            for (JsonElement el : root.getAsJsonArray("data")) {
                JsonObject obj = el.getAsJsonObject();
                if (obj.has("name") && obj.has("id")) {
                    cats.put(obj.get("name").getAsString(), obj.get("id").getAsString());
                }
            }
        }
        return cats;
    }

    public CompletableFuture<SearchResult> search(String query, String mcVersion, String loader,
                                                  ModSource source, String category, int page) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                return source == ModSource.MODRINTH
                        ? searchModrinth(query, mcVersion, loader, category, page)
                        : searchCurseForge(query, mcVersion, loader, category, page);
            } catch (Exception e) {
                System.err.println("[MOD SEARCH] Error: " + e.getMessage());
                return new SearchResult(Collections.emptyList(), 0);
            }
        }, EXECUTOR);
    }

    private SearchResult searchModrinth(String query, String mcVersion, String loader,
                                        String categorySlug, int page) throws Exception {
        StringBuilder facets = new StringBuilder("[[\"project_type:mod\"]");
        if (loader != null && !loader.isEmpty() && !loader.equalsIgnoreCase("vanilla")) {
            facets.append(",[\"categories:").append(loader.toLowerCase()).append("\"]");
        }
        if (mcVersion != null && !mcVersion.isEmpty()) {
            facets.append(",[\"versions:").append(mcVersion).append("\"]");
        }
        if (categorySlug != null && !categorySlug.isEmpty()) {
            facets.append(",[\"categories:").append(categorySlug).append("\"]");
        }
        facets.append("]");

        int offset = page * PAGE_SIZE;
        StringBuilder url = new StringBuilder("https://api.modrinth.com/v2/search")
                .append("?limit=").append(PAGE_SIZE)
                .append("&offset=").append(offset)
                .append("&index=downloads")
                .append("&facets=").append(URLEncoder.encode(facets.toString(), StandardCharsets.UTF_8));
        if (query != null && !query.isEmpty()) {
            url.append("&query=").append(URLEncoder.encode(query, StandardCharsets.UTF_8));
        }

        okhttp3.Request request = new okhttp3.Request.Builder()
                .url(url.toString())
                .addHeader("User-Agent", "MateoF24-ModpackInstaller/3.0")
                .addHeader("Accept", "application/json")
                .build();

        String json;
        try (okhttp3.Response response = new okhttp3.OkHttpClient.Builder()
                .connectTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
                .readTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
                .build().newCall(request).execute()) {
            if (!response.isSuccessful() || response.body() == null)
                return new SearchResult(Collections.emptyList(), 0);
            json = response.body().string();
        }

        JsonObject root = new Gson().fromJson(json, JsonObject.class);
        JsonArray hits = root.getAsJsonArray("hits");
        int total = root.has("total_hits") ? root.get("total_hits").getAsInt() : 0;

        List<ModResult> results = new ArrayList<>();
        if (hits != null) {
            for (JsonElement el : hits) {
                JsonObject mod = el.getAsJsonObject();
                String iconUrl = mod.has("icon_url") && !mod.get("icon_url").isJsonNull()
                        ? mod.get("icon_url").getAsString() : "";
                results.add(new ModResult(
                        mod.get("project_id").getAsString(),
                        mod.get("title").getAsString(),
                        mod.has("description") ? mod.get("description").getAsString() : "",
                        mod.has("downloads") ? mod.get("downloads").getAsLong() : 0,
                        ModSource.MODRINTH, iconUrl
                ));
            }
        }
        return new SearchResult(results, total);
    }

    private SearchResult searchCurseForge(String query, String mcVersion, String loader,
                                          String categoryId, int page) throws Exception {
        int index = page * PAGE_SIZE;
        StringBuilder url = new StringBuilder("https://api.curse.tools/v1/cf/mods/search")
                .append("?gameId=432&classId=6")
                .append("&sortField=6&sortOrder=desc")
                .append("&pageSize=").append(PAGE_SIZE)
                .append("&index=").append(index);
        if (query != null && !query.isEmpty()) {
            url.append("&searchFilter=").append(URLEncoder.encode(query, StandardCharsets.UTF_8));
        }
        if (mcVersion != null && !mcVersion.isEmpty()) {
            url.append("&gameVersion=").append(URLEncoder.encode(mcVersion, StandardCharsets.UTF_8));
        }
        if (loader != null && !loader.isEmpty() && !loader.equalsIgnoreCase("vanilla")) {
            int lt = switch (loader.toLowerCase()) {
                case "forge" -> 1; case "fabric" -> 4; case "quilt" -> 5; case "neoforge" -> 6; default -> 0;
            };
            if (lt > 0) url.append("&modLoaderType=").append(lt);
        }
        if (categoryId != null && !categoryId.isEmpty()) {
            url.append("&categoryId=").append(categoryId);
        }

        JsonObject root = new Gson().fromJson(HttpConnectionPool.getInstance().get(url.toString()), JsonObject.class);
        JsonArray data = root.getAsJsonArray("data");
        int total = root.has("pagination") && root.getAsJsonObject("pagination").has("totalCount")
                ? root.getAsJsonObject("pagination").get("totalCount").getAsInt() : 0;

        if (data == null) return new SearchResult(Collections.emptyList(), 0);

        List<ModResult> results = new ArrayList<>();
        for (JsonElement el : data) {
            JsonObject mod = el.getAsJsonObject();
            String iconUrl = mod.has("logo") && !mod.get("logo").isJsonNull()
                    && mod.getAsJsonObject("logo").has("thumbnailUrl")
                    ? mod.getAsJsonObject("logo").get("thumbnailUrl").getAsString() : "";
            results.add(new ModResult(
                    mod.get("id").getAsString(),
                    mod.get("name").getAsString(),
                    mod.has("summary") ? mod.get("summary").getAsString() : "",
                    mod.has("downloadCount") ? mod.get("downloadCount").getAsLong() : 0,
                    ModSource.CURSEFORGE, iconUrl
            ));
        }
        return new SearchResult(results, total);
    }

    public CompletableFuture<Path> installMod(ModResult mod, Path instancePath, String mcVersion, String loader) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                String url = mod.source == ModSource.MODRINTH
                        ? resolveModrinthUrl(mod.id, mcVersion, loader)
                        : resolveCurseForgeUrl(mod.id, mcVersion, loader);
                if (url == null || url.isEmpty()) throw new RuntimeException("No se pudo resolver la URL del mod");
                String fileName = url.substring(url.lastIndexOf('/') + 1);
                if (fileName.contains("?")) fileName = fileName.substring(0, fileName.indexOf('?'));
                Path modsDir = instancePath.resolve("mods");
                Files.createDirectories(modsDir);
                byte[] data = HttpConnectionPool.getInstance().getBytes(url);
                Path dest = modsDir.resolve(fileName);
                Files.write(dest, data, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
                return dest;
            } catch (Exception e) { throw new RuntimeException("Error instalando mod: " + e.getMessage(), e); }
        }, EXECUTOR);
    }

    private String resolveModrinthUrl(String projectId, String mcVersion, String loader) throws Exception {
        StringBuilder url = new StringBuilder("https://api.modrinth.com/v2/project/")
                .append(projectId).append("/version?");
        if (loader != null && !loader.isEmpty() && !loader.equalsIgnoreCase("vanilla")) {
            url.append("loaders=[\"").append(loader.toLowerCase()).append("\"]&");
        }
        if (mcVersion != null && !mcVersion.isEmpty()) {
            url.append("game_versions=[\"").append(mcVersion).append("\"]");
        }
        JsonArray versions = new Gson().fromJson(HttpConnectionPool.getInstance().get(url.toString()), JsonArray.class);
        if (versions == null || versions.size() == 0) return null;
        JsonArray files = versions.get(0).getAsJsonObject().getAsJsonArray("files");
        if (files == null || files.size() == 0) return null;
        return files.get(0).getAsJsonObject().get("url").getAsString();
    }

    private String resolveCurseForgeUrl(String projectId, String mcVersion, String loader) throws Exception {
        String urlStr = "https://api.curse.tools/v1/cf/mods/" + projectId + "/files?pageSize=5"
                + (mcVersion != null ? "&gameVersion=" + URLEncoder.encode(mcVersion, StandardCharsets.UTF_8) : "");
        JsonObject root = new Gson().fromJson(HttpConnectionPool.getInstance().get(urlStr), JsonObject.class);
        JsonArray data = root.getAsJsonArray("data");
        if (data == null || data.size() == 0) return null;
        for (JsonElement el : data) {
            JsonObject file = el.getAsJsonObject();
            if (file.has("downloadUrl") && !file.get("downloadUrl").isJsonNull())
                return file.get("downloadUrl").getAsString();
        }
        JsonObject first = data.get(0).getAsJsonObject();
        if (first.has("id") && first.has("fileName")) {
            int fileId = first.get("id").getAsInt();
            return String.format("https://mediafiles.forgecdn.net/files/%d/%d/%s",
                    fileId / 1000, fileId % 1000, first.get("fileName").getAsString());
        }
        return null;
    }

    public static void shutdown() { EXECUTOR.shutdown(); }

    public static class SearchResult {
        public final List<ModResult> mods;
        public final int totalHits;
        public SearchResult(List<ModResult> mods, int totalHits) {
            this.mods = mods;
            this.totalHits = totalHits;
        }
    }
}