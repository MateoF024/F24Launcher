package bundle.mods;

public class ModResult {
    public final String id;
    public final String name;
    public final String description;
    public final long downloads;
    public final ModSource source;
    public final String iconUrl;

    public ModResult(String id, String name, String description, long downloads, ModSource source, String iconUrl) {
        this.id = id;
        this.name = name;
        this.description = description;
        this.downloads = downloads;
        this.source = source;
        this.iconUrl = iconUrl != null ? iconUrl : "";
    }

    @Override
    public String toString() { return name; }
}