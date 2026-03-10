package bundle.config;


import bundle.util.HttpConnectionPool;

import com.google.gson.Gson;
import com.google.gson.JsonObject;

import java.io.*;

public final class RemoteConfigLoader {

    private static final String GIST_ID = "50ece138114a907d053a2622ff900fa4";
    private static final String FILE_NAME = "installer_config.json";
    private static final String GITHUB_API_URL = "https://api.github.com/gists/" + GIST_ID;
    private static final String FALLBACK_RAW_URL =
            "https://gist.githubusercontent.com/MateoF024/50ece138114a907d053a2622ff900fa4/raw/installer_config.json";

    private static final int CONNECT_TIMEOUT = 10_000;
    private static final int READ_TIMEOUT = 15_000;

    private RemoteConfigLoader() { }

    public static JsonObject loadRemoteConfig() {
        System.out.println("=== Cargando configuración remota ===");

        JsonObject config = loadFromGitHubAPI();
        if (config != null) {
            System.out.println("✓ Configuración cargada desde GitHub API");
            return config;
        }

        System.out.println("⚠ API falló, intentando con URL raw como fallback...");
        config = loadFromRawUrl(FALLBACK_RAW_URL);
        if (config != null) {
            System.out.println("✓ Configuración cargada desde URL raw");
            return config;
        }

        System.err.println("✗ No se pudo cargar la configuración remota");
        return null;
    }

    private static JsonObject loadFromGitHubAPI() {
        try {
            System.out.println("Consultando GitHub API: " + GITHUB_API_URL);

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

            System.err.println("✗ No se encontró el archivo '" + FILE_NAME + "' en el Gist");
            return null;

        } catch (Exception e) {
            System.err.println("✗ Error al consultar GitHub API: " + e.getMessage());
            return null;
        }
    }

    private static JsonObject loadFromRawUrl(String rawUrl) {
        try {
            System.out.println("Consultando URL raw: " + rawUrl);

            String jsonResponse = HttpConnectionPool.getInstance().get(rawUrl);
            Gson gson = new Gson();
            return gson.fromJson(jsonResponse, JsonObject.class);

        } catch (Exception e) {
            System.err.println("✗ Error al cargar desde URL raw: " + e.getMessage());
            return null;
        }
    }

    public static boolean isValidConfig(JsonObject config) {
        if (config == null) {
            return false;
        }

        if (!config.has("modpacks") || !config.get("modpacks").isJsonObject()) {
            System.err.println("✗ Configuración remota inválida: falta sección 'modpacks'");
            return false;
        }

        JsonObject modpacks = config.getAsJsonObject("modpacks");
        if (modpacks.size() == 0) {
            System.err.println("✗ Configuración remota inválida: sección 'modpacks' está vacía");
            return false;
        }

        System.out.println("✓ Configuración válida con " + modpacks.size() + " modpack(s)");
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