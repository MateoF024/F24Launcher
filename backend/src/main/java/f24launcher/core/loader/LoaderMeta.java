package f24launcher.core.loader;

import f24launcher.util.HttpConnectionPool;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/** Cliente de metadatos de mod loaders (Fabric y Quilt). */
public class LoaderMeta {

    private final HttpConnectionPool http = HttpConnectionPool.getInstance();

    public boolean supported(String type) {
        return "fabric".equals(type) || "quilt".equals(type);
    }

    /** Versiones de loader compatibles con una versión de Minecraft (más nuevas primero). */
    public List<String> listVersions(String type, String mcVersion) throws IOException {
        String base = baseFor(type);
        if (base == null) return List.of();
        String url = base + "/versions/loader/" + mcVersion;
        JsonArray arr = JsonParser.parseString(http.get(url)).getAsJsonArray();
        List<String> out = new ArrayList<>();
        for (int i = 0; i < arr.size(); i++) {
            JsonObject loader = arr.get(i).getAsJsonObject().getAsJsonObject("loader");
            if (loader != null && loader.has("version")) out.add(loader.get("version").getAsString());
        }
        return out;
    }

    /** Perfil de versión (JSON listo para fusionar con la vanilla). */
    public String profileJson(String type, String mcVersion, String loaderVersion) throws IOException {
        String base = baseFor(type);
        if (base == null) throw new IOException("Loader no soportado: " + type);
        return http.get(base + "/versions/loader/" + mcVersion + "/" + loaderVersion + "/profile/json");
    }

    private String baseFor(String type) {
        return switch (type) {
            case "fabric" -> "https://meta.fabricmc.net/v2";
            case "quilt" -> "https://meta.quiltmc.org/v3";
            default -> null;
        };
    }
}
