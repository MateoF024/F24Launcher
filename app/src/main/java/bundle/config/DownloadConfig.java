package bundle.config;

import java.util.List;

public final class DownloadConfig {
    public final String name;
    public final List<String> urls;
    public final String version;
    public final String loader;
    public final String description;
    public final String iconUrl;

    public DownloadConfig(String name, String url, String version, String loader, String description, String iconUrl) {
        this.name = name;
        this.urls = List.of(url);
        this.version = version != null ? version : "";
        this.loader = loader != null ? loader : "";
        this.description = description != null ? description : "";
        this.iconUrl = iconUrl != null ? iconUrl : "";
    }

    public DownloadConfig(String name, String url) {
        this(name, url, "", "", "", "");
    }

    @Override
    public String toString() {
        return "DownloadConfig{name='" + name + "', urls=" + urls + '}';
    }
}