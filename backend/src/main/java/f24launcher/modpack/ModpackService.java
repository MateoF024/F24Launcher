package f24launcher.modpack;

import f24launcher.modpack.ModpackModels.Modpack;
import f24launcher.util.HttpConnectionPool;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Carga el catálogo de modpacks privados desde installer_config.json en GitHub.
 *
 * SIEMPRE se consulta desde internet (API del Gist y, si falla, la URL raw, ambas
 * remotas). No se guarda ninguna copia ni fallback local en la app.
 */
public class ModpackService {

    private static final Logger log = LoggerFactory.getLogger(ModpackService.class);

    private static final String GIST_ID = "50ece138114a907d053a2622ff900fa4";
    private static final String GIST_OWNER = "MateoF024";
    private static final String FILE_NAME = "installer_config.json";
    private static final String API_URL = "https://api.github.com/gists/" + GIST_ID;
    private static final String RAW_URL =
            "https://gist.githubusercontent.com/" + GIST_OWNER + "/" + GIST_ID + "/raw/" + FILE_NAME;

    private final Gson gson = new Gson();
    private final HttpConnectionPool http = HttpConnectionPool.getInstance();

    public List<Modpack> list() throws IOException {
        JsonObject root = fetch();
        if (root == null || !root.has("modpacks") || !root.get("modpacks").isJsonObject())
            throw new IOException("installer_config.json remoto inválido o inaccesible");

        JsonObject modpacks = root.getAsJsonObject("modpacks");
        List<Modpack> out = new ArrayList<>();
        for (Map.Entry<String, JsonElement> e : modpacks.entrySet()) {
            String name = e.getKey();
            JsonElement v = e.getValue();
            String urlStandard = "", urlLite = "", version = "";
            String mc = "", loader = "", lv = "", icon = "", summary = "", descUrl = "";

            if (v.isJsonPrimitive() && v.getAsJsonPrimitive().isString()) {
                // Descriptor legado: solo una URL → se interpreta como variante estándar.
                urlStandard = v.getAsString().trim();
            } else if (v.isJsonObject()) {
                JsonObject o = v.getAsJsonObject();
                // Esquema 0.0.5: url_standar / url_lite. Retrocompat: url / downloadUrl → estándar.
                urlStandard = first(str(o, "url_standar"), first(str(o, "urlStandard"),
                        first(str(o, "url"), str(o, "downloadUrl"))));
                urlLite = first(str(o, "url_lite"), str(o, "urlLite"));
                version = str(o, "version");
                mc = first(str(o, "mcVersion"), str(o, "gameVersion"));
                loader = str(o, "loader");
                lv = str(o, "loaderVersion");
                icon = str(o, "icon");
                summary = first(str(o, "summary"), str(o, "description"));
                descUrl = first(str(o, "descriptionUrl"), str(o, "readme"));
                if (!str(o, "name").isBlank()) name = str(o, "name");
            } else {
                continue;
            }
            if (urlStandard.isBlank()) {
                log.warn("Modpack '{}' sin URL válida; se omite.", name);
                continue;
            }
            // El formato se deduce de la URL estándar (las dos variantes comparten formato).
            out.add(new Modpack(slug(name), name, version, urlStandard, urlLite,
                    detectFormat(urlStandard), mc, loader, lv, icon, summary, descUrl));
        }
        return out;
    }

    private JsonObject fetch() {
        // Raw primero con cache-busting (siempre fresco, sin límite de tasa);
        // la API del Gist queda como respaldo.
        JsonObject raw = fromRaw();
        if (raw != null) return raw;
        log.warn("URL raw no disponible; intentando con la API del Gist.");
        return fromApi();
    }

    /** Añade un parámetro anti-caché para evitar copias obsoletas del CDN de GitHub. */
    public static String cacheBust(String url) {
        if (url == null || url.isBlank()) return url;
        return url + (url.contains("?") ? "&" : "?") + "_=" + System.currentTimeMillis();
    }

    private JsonObject fromApi() {
        try {
            JsonObject gist = gson.fromJson(http.get(API_URL), JsonObject.class);
            if (gist != null && gist.has("files")) {
                JsonObject files = gist.getAsJsonObject("files");
                if (files.has(FILE_NAME)) {
                    JsonObject fi = files.getAsJsonObject(FILE_NAME);
                    if (fi.has("content"))
                        return gson.fromJson(fi.get("content").getAsString(), JsonObject.class);
                }
            }
        } catch (Exception e) {
            log.warn("Error consultando Gist API: {}", e.getMessage());
        }
        return null;
    }

    private JsonObject fromRaw() {
        try {
            return gson.fromJson(http.get(cacheBust(RAW_URL)), JsonObject.class);
        } catch (Exception e) {
            log.warn("Error consultando Gist raw: {}", e.getMessage());
            return null;
        }
    }

    /**
     * Convierte enlaces de página de GitHub a su forma raw (texto plano):
     * {@code gist.github.com/<u>/<id>} → {@code gist.githubusercontent.com/<u>/<id>/raw/} y
     * {@code github.com/.../blob/...} → {@code raw.githubusercontent.com/.../...}.
     * Una URL ya raw se devuelve sin cambios.
     */
    public static String toRawUrl(String url) {
        if (url == null || url.isBlank()) return "";
        String u = url.trim();
        try {
            java.net.URI uri = java.net.URI.create(u);
            String host = uri.getHost() == null ? "" : uri.getHost();
            String path = uri.getPath() == null ? "" : uri.getPath();
            if (host.equals("gist.github.com")) {
                String p = path.startsWith("/") ? path.substring(1) : path;
                String[] parts = p.split("/");
                if (parts.length >= 2)
                    return "https://gist.githubusercontent.com/" + parts[0] + "/" + parts[1] + "/raw/";
            }
            if (host.equals("github.com") && path.contains("/blob/")) {
                return "https://raw.githubusercontent.com" + path.replaceFirst("/blob/", "/");
            }
        } catch (Exception ignored) {}
        return u;
    }

    private static String detectFormat(String url) {
        String u = url.toLowerCase(Locale.ROOT);
        if (u.endsWith(".mrpack")) return "mrpack";
        if (u.endsWith(".zip")) return "zip";
        return "";
    }

    private static String str(JsonObject o, String key) {
        return o.has(key) && !o.get(key).isJsonNull() ? o.get(key).getAsString() : "";
    }

    private static String first(String a, String b) {
        return a == null || a.isBlank() ? (b == null ? "" : b) : a;
    }

    private static String slug(String name) {
        String s = name.trim().toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9]+", "-").replaceAll("(^-+|-+$)", "");
        return s.isEmpty() ? "modpack" : s;
    }
}
