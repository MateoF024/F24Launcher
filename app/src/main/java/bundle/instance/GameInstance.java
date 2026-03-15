package bundle.instance;

import com.google.gson.annotations.Expose;

public class GameInstance {
    @Expose public String name;
    @Expose public String path;
    @Expose public String minecraftVersion;
    @Expose public String loader;
    @Expose public String loaderVersion;
    @Expose public String detectedLauncher;

    public GameInstance() {}

    public GameInstance(String name, String path, String minecraftVersion,
                        String loader, String loaderVersion, String detectedLauncher) {
        this.name = name;
        this.path = path;
        this.minecraftVersion = minecraftVersion;
        this.loader = loader;
        this.loaderVersion = loaderVersion;
        this.detectedLauncher = detectedLauncher;
    }

    public boolean isSupported() {
        return loader != null && !loader.equalsIgnoreCase("vanilla")
                && !loader.equalsIgnoreCase("unknown") && !loader.isEmpty();
    }

    @Override
    public String toString() {
        if (name == null) return "";
        String info = "";
        if (minecraftVersion != null && !minecraftVersion.isEmpty()) info += " [" + minecraftVersion;
        if (loader != null && !loader.isEmpty() && !loader.equalsIgnoreCase("unknown")) info += " · " + loader;
        if (!info.isEmpty()) info += "]";
        return name + info;
    }
}