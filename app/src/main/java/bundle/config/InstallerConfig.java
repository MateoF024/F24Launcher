package bundle.config;

import java.util.*;

public final class InstallerConfig {
    public final Map<String, DownloadConfig> configs;
    public final List<String> configNames;

    private InstallerConfig(LinkedHashMap<String, DownloadConfig> source) {
        this.configs = Collections.unmodifiableMap(source);
        this.configNames = List.copyOf(source.keySet());
    }

    @Override
    public String toString() {
        return String.format("%s { configs: %s }", getClass().getName(), configs);
    }

    public static class Builder {
        private final LinkedHashMap<String, DownloadConfig> configs = new LinkedHashMap<>();

        public Builder with(String id, DownloadConfig download) {
            configs.put(id, download);
            return this;
        }

        public InstallerConfig build() {
            return new InstallerConfig(configs);
        }
    }
}