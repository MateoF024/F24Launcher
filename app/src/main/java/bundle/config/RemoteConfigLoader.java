package bundle.config;

import bundle.util.HttpConnectionPool;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class RemoteConfigLoader {

    private static final Logger log = LoggerFactory.getLogger(RemoteConfigLoader.class);

    private static final String GIST_ID        = "50ece138114a907d053a2622ff900fa4";
    private static final String FILE_NAME      = "installer_config.json";
    private static final String GITHUB_API_URL = "https://api.github.com/gists/" + GIST_ID;
    private static final String FALLBACK_RAW_URL =
            "https://gist.githubusercontent.com/MateoF024/50ece138114a907d053a2622ff900fa4/raw/installer_config.json";

    private RemoteConfigLoader() { }

    public static JsonObject loadRemoteConfig() {
        log.info("Cargando configuración remota...");

        JsonObject config = loadFromGitHubAPI();
        if (config != null) {
            log.info("Configuración cargada desde GitHub API");
            return config;
        }

        log.warn("API falló, intentando con URL raw como fallback...");
        config = loadFromRawUrl(FALLBACK_RAW_URL);
        if (config != null) {
            log.info("Configuración cargada desde URL raw");
            return config;
        }

        log.error("No se pudo cargar la configuración remota");
        return null;
    }

    private static JsonObject loadFromGitHubAPI() {
        try {
            log.info("Consultando GitHub API: {}", GITHUB_API_URL);

            String jsonResponse = HttpConnectionPool.getInstance().get(GITHUB_API_URL);
            Gson gson = new Gson();
            JsonObject gistResponse = gson.fromJson(jsonResponse, JsonObject.class);

            if (gistResponse.has("files")) {
                JsonObject files = gistResponse.getAsJsonObject("files");
                if (files.has(FILE_NAME)) {
                    JsonObject fileInfo = files.getAsJsonObject(FILE_NAME);
                    if (fileInfo.has("content")) {
                        String content = fileInfo.get("content").getAsString();
                        return gson.fromJson(content, JsonObject.class);
                    }
                }
            }

            log.error("No se encontró el archivo '{}' en el Gist", FILE_NAME);
            return null;

        } catch (Exception e) {
            log.error("Error al consultar GitHub API: {}", e.getMessage());
            return null;
        }
    }

    private static JsonObject loadFromRawUrl(String rawUrl) {
        try {
            log.info("Consultando URL raw: {}", rawUrl);

            String jsonResponse = HttpConnectionPool.getInstance().get(rawUrl);
            return new Gson().fromJson(jsonResponse, JsonObject.class);

        } catch (Exception e) {
            log.error("Error al cargar desde URL raw: {}", e.getMessage());
            return null;
        }
    }

    public static boolean isValidConfig(JsonObject config) {
        if (config == null) return false;

        if (!config.has("modpacks") || !config.get("modpacks").isJsonObject()) {
            log.error("Configuración remota inválida: falta sección 'modpacks'");
            return false;
        }

        JsonObject modpacks = config.getAsJsonObject("modpacks");
        if (modpacks.size() == 0) {
            log.error("Configuración remota inválida: sección 'modpacks' está vacía");
            return false;
        }

        log.info("Configuración válida con {} modpack(s)", modpacks.size());
        return true;
    }

    public static JsonObject loadAndValidateRemoteConfig() {
        JsonObject config = loadRemoteConfig();
        if (config != null && isValidConfig(config)) {
            return config;
        }
        return null;
    }
}