package bundle.config;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.Map;

public final class ConfigParser {

    private ConfigParser() { }

    public static InstallerConfig parse(JsonObject root) throws ConfigParseException {
        if (root == null) throw new ConfigParseException("Config JSON is null");
        if (!root.has("modpacks") || !root.get("modpacks").isJsonObject())
            throw new ConfigParseException("Missing or invalid 'modpacks' section in config JSON");

        JsonObject modpacks = root.getAsJsonObject("modpacks");
        InstallerConfig.Builder builder = new InstallerConfig.Builder();

        for (Map.Entry<String, JsonElement> entry : modpacks.entrySet()) {
            String modpackName = entry.getKey();
            JsonElement value = entry.getValue();

            if (value.isJsonPrimitive() && value.getAsJsonPrimitive().isString()) {
                String url = value.getAsString().trim();
                if (url.isEmpty()) continue;
                builder.with(modpackName, new DownloadConfig(modpackName, url));
            } else if (value.isJsonObject()) {
                JsonObject obj = value.getAsJsonObject();
                if (!obj.has("url")) continue;
                String url = obj.get("url").getAsString().trim();
                if (url.isEmpty()) continue;
                String version     = obj.has("version")     ? obj.get("version").getAsString()     : "";
                String loader      = obj.has("loader")      ? obj.get("loader").getAsString()      : "";
                String description = obj.has("description") ? obj.get("description").getAsString() : "";
                String icon        = obj.has("icon")        ? obj.get("icon").getAsString()        : "";
                builder.with(modpackName, new DownloadConfig(modpackName, url, version, loader, description, icon));
            }
        }
        return builder.build();
    }
}