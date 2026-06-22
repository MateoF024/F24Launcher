package f24launcher.content;

import f24launcher.content.ContentModels.*;
import f24launcher.util.HttpConnectionPool;

import com.google.gson.*;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * Consulta las APIs de Modrinth y CurseForge (vía proxy api.curse.tools) para
 * buscar contenido, leer la página de un proyecto y listar sus versiones.
 * Las descargas reales las realiza {@link ContentInstaller}.
 */
public class ContentService {

    public static final int PAGE_SIZE = 20;

    private static final String MR = "https://api.modrinth.com/v2";
    private static final String CF = "https://api.curse.tools/v1/cf";
    private static final int CF_GAME = 432;

    private final Gson gson = new Gson();

    // ── Búsqueda ──────────────────────────────────────────────────────

    public SearchResult search(String source, ContentType type, String mc, String loader,
                               String query, String category, String environment, String sort,
                               int page, boolean ignoreFilters) throws Exception {
        return "curseforge".equals(source)
                ? searchCurseForge(type, mc, loader, query, category, sort, page, ignoreFilters)
                : searchModrinth(type, mc, loader, query, category, environment, sort, page, ignoreFilters);
    }

    private SearchResult searchModrinth(ContentType type, String mc, String loader, String query,
                                        String category, String environment, String sort,
                                        int page, boolean ignore) throws Exception {
        StringBuilder facets = new StringBuilder("[[\"project_type:").append(type.modrinth).append("\"]");
        if (!ignore) {
            if (mc != null && !mc.isBlank()) facets.append(",[\"versions:").append(mc).append("\"]");
            if (type.usesLoader() && loader != null && !loader.isBlank() && !loader.equalsIgnoreCase("vanilla"))
                facets.append(",[\"categories:").append(loader.toLowerCase()).append("\"]");
        }
        for (String c : splitCsv(category)) facets.append(",[\"categories:").append(c).append("\"]");
        for (String env : splitCsv(environment)) {
            if (env.equalsIgnoreCase("client"))
                facets.append(",[\"client_side:optional\",\"client_side:required\"]");
            else if (env.equalsIgnoreCase("server"))
                facets.append(",[\"server_side:optional\",\"server_side:required\"]");
        }
        facets.append("]");

        String index = switch (sort == null ? "" : sort) {
            case "downloads" -> "downloads";
            case "follows" -> "follows";
            case "newest" -> "newest";
            case "updated" -> "updated";
            default -> "relevance";
        };

        StringBuilder url = new StringBuilder(MR).append("/search?limit=").append(PAGE_SIZE)
                .append("&offset=").append(page * PAGE_SIZE)
                .append("&index=").append(index)
                .append("&facets=").append(enc(facets.toString()));
        if (query != null && !query.isBlank()) url.append("&query=").append(enc(query));

        JsonObject root = obj(http(url.toString()));
        int total = root.has("total_hits") ? root.get("total_hits").getAsInt() : 0;
        List<Project> hits = new ArrayList<>();
        if (root.has("hits")) for (JsonElement el : root.getAsJsonArray("hits")) {
            JsonObject m = el.getAsJsonObject();
            hits.add(new Project(
                    str(m, "project_id"), str(m, "slug"), "modrinth", type.id,
                    str(m, "title"), str(m, "description"), str(m, "author"), str(m, "icon_url"),
                    lng(m, "downloads"), lng(m, "follows"), strList(m, "display_categories", "categories"),
                    str(m, "date_modified"), str(m, "client_side"), str(m, "server_side")));
        }
        return new SearchResult(hits, total);
    }

    private SearchResult searchCurseForge(ContentType type, String mc, String loader, String query,
                                          String category, String sort, int page, boolean ignore) throws Exception {
        if (type.cfClassId < 0) return new SearchResult(List.of(), 0);
        StringBuilder url = new StringBuilder(CF).append("/mods/search?gameId=").append(CF_GAME)
                .append("&classId=").append(type.cfClassId)
                .append("&pageSize=").append(PAGE_SIZE).append("&index=").append(page * PAGE_SIZE);
        int sortField = switch (sort == null ? "" : sort) {
            case "downloads" -> 6;
            case "follows" -> 2;
            case "newest", "updated" -> 3;
            default -> 1;
        };
        url.append("&sortField=").append(sortField).append("&sortOrder=desc");
        if (query != null && !query.isBlank()) url.append("&searchFilter=").append(enc(query));
        if (!ignore) {
            if (mc != null && !mc.isBlank()) url.append("&gameVersion=").append(enc(mc));
            if (type.usesLoader()) {
                int lt = cfLoader(loader);
                if (lt > 0) url.append("&modLoaderType=").append(lt);
            }
        }
        List<String> cats = splitCsv(category);
        if (cats.size() == 1) url.append("&categoryId=").append(cats.get(0));
        else if (cats.size() > 1) url.append("&categoryIds=").append(enc("[" + String.join(",", cats) + "]"));

        JsonObject root = obj(http(url.toString()));
        int total = 0;
        if (root.has("pagination") && root.getAsJsonObject("pagination").has("totalCount"))
            total = Math.min(root.getAsJsonObject("pagination").get("totalCount").getAsInt(), 10000);
        List<Project> hits = new ArrayList<>();
        if (root.has("data")) for (JsonElement el : root.getAsJsonArray("data")) {
            JsonObject m = el.getAsJsonObject();
            hits.add(new Project(
                    str(m, "id"), str(m, "slug"), "curseforge", type.id,
                    str(m, "name"), str(m, "summary"), cfAuthor(m), cfLogo(m),
                    lng(m, "downloadCount"), 0, cfCategories(m), str(m, "dateModified"), "", ""));
        }
        return new SearchResult(hits, total);
    }

    // ── Página del proyecto ───────────────────────────────────────────

    public ProjectDetail project(String source, ContentType type, String id) throws Exception {
        return "curseforge".equals(source) ? curseForgeProject(type, id) : modrinthProject(type, id);
    }

    private ProjectDetail modrinthProject(ContentType type, String id) throws Exception {
        JsonObject m = obj(http(MR + "/project/" + enc(id)));
        List<String> gallery = new ArrayList<>();
        if (m.has("gallery")) for (JsonElement g : m.getAsJsonArray("gallery")) {
            JsonObject go = g.getAsJsonObject();
            if (go.has("url")) gallery.add(go.get("url").getAsString());
        }
        Map<String, String> links = new LinkedHashMap<>();
        putIf(links, "Código fuente", str(m, "source_url"));
        putIf(links, "Issues", str(m, "issues_url"));
        putIf(links, "Wiki", str(m, "wiki_url"));
        putIf(links, "Discord", str(m, "discord_url"));
        links.put("Modrinth", "https://modrinth.com/" + type.modrinth + "/" + str(m, "slug"));
        String license = "";
        if (m.has("license") && m.get("license").isJsonObject()) {
            JsonObject lic = m.getAsJsonObject("license");
            license = lic.has("name") ? lic.get("name").getAsString() : str(lic, "id");
        }
        return new ProjectDetail(
                str(m, "id"), str(m, "slug"), "modrinth", type.id, str(m, "title"),
                str(m, "description"), str(m, "body"), "markdown", str(m, "icon_url"),
                "", lng(m, "downloads"), lng(m, "followers"), strList(m, "categories", "categories"),
                gallery, links, license, str(m, "client_side"), str(m, "server_side"));
    }

    private ProjectDetail curseForgeProject(ContentType type, String id) throws Exception {
        JsonObject m = obj(http(CF + "/mods/" + enc(id))).getAsJsonObject("data");
        String body = "";
        try {
            JsonObject d = obj(http(CF + "/mods/" + enc(id) + "/description"));
            if (d.has("data")) body = d.get("data").getAsString();
        } catch (Exception ignored) {}
        List<String> gallery = new ArrayList<>();
        if (m.has("screenshots")) for (JsonElement s : m.getAsJsonArray("screenshots")) {
            JsonObject so = s.getAsJsonObject();
            if (so.has("url")) gallery.add(so.get("url").getAsString());
        }
        Map<String, String> links = new LinkedHashMap<>();
        if (m.has("links") && m.get("links").isJsonObject()) {
            JsonObject l = m.getAsJsonObject("links");
            putIf(links, "Sitio web", str(l, "websiteUrl"));
            putIf(links, "Código fuente", str(l, "sourceUrl"));
            putIf(links, "Issues", str(l, "issuesUrl"));
            putIf(links, "Wiki", str(l, "wikiUrl"));
        }
        return new ProjectDetail(
                str(m, "id"), str(m, "slug"), "curseforge", type.id, str(m, "name"),
                str(m, "summary"), body, "html", cfLogo(m), cfAuthor(m),
                lng(m, "downloadCount"), 0, cfCategories(m), gallery, links, "", "", "");
    }

    // ── Identificación por hash (mods externos) ───────────────────────

    public record ModrinthHit(String projectId, String versionId, String versionNumber) {}

    /** Busca una versión de Modrinth por el SHA1 del archivo; null si no existe. */
    public ModrinthHit modrinthByHash(String sha1) {
        try {
            JsonObject v = obj(http(MR + "/version_file/" + sha1 + "?algorithm=sha1"));
            if (v == null || !v.has("project_id")) return null;
            return new ModrinthHit(str(v, "project_id"), str(v, "id"), str(v, "version_number"));
        } catch (Exception e) {
            return null;
        }
    }

    // ── Versiones ─────────────────────────────────────────────────────

    public List<Version> versions(String source, ContentType type, String id,
                                  String mc, String loader, boolean ignore) throws Exception {
        return "curseforge".equals(source)
                ? curseForgeVersions(type, id, mc, loader, ignore)
                : modrinthVersions(type, id, mc, loader, ignore);
    }

    private List<Version> modrinthVersions(ContentType type, String id, String mc,
                                           String loader, boolean ignore) throws Exception {
        StringBuilder url = new StringBuilder(MR).append("/project/").append(enc(id)).append("/version");
        boolean first = true;
        if (!ignore) {
            if (type.usesLoader() && loader != null && !loader.isBlank() && !loader.equalsIgnoreCase("vanilla")) {
                url.append("?loaders=").append(enc("[\"" + loader.toLowerCase() + "\"]"));
                first = false;
            }
            if (mc != null && !mc.isBlank()) {
                url.append(first ? "?" : "&").append("game_versions=").append(enc("[\"" + mc + "\"]"));
            }
        }
        JsonArray arr = arr(http(url.toString()));
        List<Version> out = new ArrayList<>();
        for (JsonElement el : arr) {
            JsonObject v = el.getAsJsonObject();
            JsonObject file = primaryFile(v.getAsJsonArray("files"));
            if (file == null) continue;
            List<Dependency> deps = new ArrayList<>();
            if (v.has("dependencies")) for (JsonElement de : v.getAsJsonArray("dependencies")) {
                JsonObject d = de.getAsJsonObject();
                if (d.has("project_id") && !d.get("project_id").isJsonNull())
                    deps.add(new Dependency(d.get("project_id").getAsString(), str(d, "dependency_type")));
            }
            out.add(new Version(
                    str(v, "id"), str(v, "name"), str(v, "version_number"), str(v, "version_type"),
                    str(file, "filename"), str(file, "url"), lng(file, "size"),
                    strList(v, "game_versions", "game_versions"), strList(v, "loaders", "loaders"),
                    str(v, "date_published"), deps));
        }
        return out;
    }

    private JsonObject primaryFile(JsonArray files) {
        if (files == null || files.isEmpty()) return null;
        for (JsonElement f : files) {
            JsonObject fo = f.getAsJsonObject();
            if (fo.has("primary") && fo.get("primary").getAsBoolean()) return fo;
        }
        return files.get(0).getAsJsonObject();
    }

    private List<Version> curseForgeVersions(ContentType type, String id, String mc,
                                             String loader, boolean ignore) throws Exception {
        StringBuilder url = new StringBuilder(CF).append("/mods/").append(enc(id)).append("/files?pageSize=50");
        if (!ignore) {
            if (mc != null && !mc.isBlank()) url.append("&gameVersion=").append(enc(mc));
            if (type.usesLoader()) {
                int lt = cfLoader(loader);
                if (lt > 0) url.append("&modLoaderType=").append(lt);
            }
        }
        JsonObject root = obj(http(url.toString()));
        List<Version> out = new ArrayList<>();
        if (root.has("data")) for (JsonElement el : root.getAsJsonArray("data")) {
            JsonObject f = el.getAsJsonObject();
            String dl = cfDownloadUrl(f);
            if (dl == null) continue;
            String channel = switch (f.has("releaseType") ? f.get("releaseType").getAsInt() : 1) {
                case 2 -> "beta"; case 3 -> "alpha"; default -> "release";
            };
            List<Dependency> deps = new ArrayList<>();
            if (f.has("dependencies")) for (JsonElement de : f.getAsJsonArray("dependencies")) {
                JsonObject d = de.getAsJsonObject();
                if (d.has("modId") && d.has("relationType") && d.get("relationType").getAsInt() == 3)
                    deps.add(new Dependency(d.get("modId").getAsString(), "required"));
            }
            out.add(new Version(
                    str(f, "id"), str(f, "displayName"), str(f, "fileName"), channel,
                    str(f, "fileName"), dl, lng(f, "fileLength"),
                    strList(f, "gameVersions", "gameVersions"), List.of(),
                    str(f, "fileDate"), deps));
        }
        return out;
    }

    // ── Categorías (facetas) ──────────────────────────────────────────

    public List<Category> categories(String source, ContentType type) throws Exception {
        List<Category> out = new ArrayList<>();
        if ("curseforge".equals(source)) {
            if (type.cfClassId < 0) return out;
            String cls = String.valueOf(type.cfClassId);
            JsonObject root = obj(http(CF + "/categories?gameId=" + CF_GAME + "&classId=" + type.cfClassId));
            if (root.has("data")) for (JsonElement el : root.getAsJsonArray("data")) {
                JsonObject c = el.getAsJsonObject();
                if (!c.has("name") || !c.has("id")) continue;
                String id = c.get("id").getAsString();
                String parent = str(c, "parentCategoryId");
                // La clase raíz (classId) no es un padre real: esas categorías son de primer nivel.
                if (parent.equals(cls)) parent = "";
                out.add(new Category(id, c.get("name").getAsString(), parent));
            }
        } else {
            JsonArray arr = arr(http(MR + "/tag/category"));
            for (JsonElement el : arr) {
                JsonObject c = el.getAsJsonObject();
                if (type.modrinth.equals(str(c, "project_type"))) {
                    String n = str(c, "name");
                    out.add(new Category(n, n, ""));
                }
            }
        }
        return out;
    }

    // ── Helpers ───────────────────────────────────────────────────────

    private String cfDownloadUrl(JsonObject file) {
        if (file.has("downloadUrl") && !file.get("downloadUrl").isJsonNull())
            return file.get("downloadUrl").getAsString();
        if (file.has("id") && file.has("fileName")) {
            long fid = file.get("id").getAsLong();
            return String.format("https://mediafilez.forgecdn.net/files/%d/%d/%s",
                    fid / 1000, fid % 1000, str(file, "fileName"));
        }
        return null;
    }

    private int cfLoader(String loader) {
        if (loader == null) return 0;
        return switch (loader.toLowerCase()) {
            case "forge" -> 1; case "fabric" -> 4; case "quilt" -> 5; case "neoforge" -> 6; default -> 0;
        };
    }

    private String cfLogo(JsonObject m) {
        if (m.has("logo") && m.get("logo").isJsonObject()) {
            JsonObject l = m.getAsJsonObject("logo");
            if (l.has("thumbnailUrl")) return l.get("thumbnailUrl").getAsString();
            if (l.has("url")) return l.get("url").getAsString();
        }
        return "";
    }

    private String cfAuthor(JsonObject m) {
        if (m.has("authors") && m.get("authors").isJsonArray()) {
            JsonArray a = m.getAsJsonArray("authors");
            if (!a.isEmpty() && a.get(0).getAsJsonObject().has("name"))
                return a.get(0).getAsJsonObject().get("name").getAsString();
        }
        return "";
    }

    private List<String> cfCategories(JsonObject m) {
        List<String> out = new ArrayList<>();
        if (m.has("categories") && m.get("categories").isJsonArray())
            for (JsonElement c : m.getAsJsonArray("categories")) {
                JsonObject co = c.getAsJsonObject();
                if (co.has("name")) out.add(co.get("name").getAsString());
            }
        return out;
    }

    private void putIf(Map<String, String> map, String key, String val) {
        if (val != null && !val.isBlank()) map.put(key, val);
    }

    private static List<String> splitCsv(String s) {
        List<String> out = new ArrayList<>();
        if (s == null || s.isBlank()) return out;
        for (String p : s.split(",")) { String t = p.trim(); if (!t.isEmpty()) out.add(t); }
        return out;
    }

    /** Descarga texto plano (p. ej. markdown remoto) a través del backend. */
    public String fetchText(String url) throws Exception { return http(url); }

    private String http(String url) throws Exception { return HttpConnectionPool.getInstance().get(url); }
    private JsonObject obj(String json) { return gson.fromJson(json, JsonObject.class); }
    private JsonArray arr(String json) { return gson.fromJson(json, JsonArray.class); }

    private static String enc(String s) { return URLEncoder.encode(s, StandardCharsets.UTF_8); }

    private static String str(JsonObject o, String key) {
        return o != null && o.has(key) && !o.get(key).isJsonNull() ? o.get(key).getAsString() : "";
    }

    private static long lng(JsonObject o, String key) {
        return o != null && o.has(key) && !o.get(key).isJsonNull() ? o.get(key).getAsLong() : 0;
    }

    private static List<String> strList(JsonObject o, String key, String fallback) {
        String k = o.has(key) ? key : fallback;
        List<String> out = new ArrayList<>();
        if (o.has(k) && o.get(k).isJsonArray())
            for (JsonElement e : o.getAsJsonArray(k)) if (!e.isJsonNull()) out.add(e.getAsString());
        return out;
    }
}
