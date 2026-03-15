package bundle.mods;

public enum ModSource {
    MODRINTH("Modrinth"),
    CURSEFORGE("CurseForge");

    public final String displayName;

    ModSource(String displayName) { this.displayName = displayName; }

    @Override
    public String toString() { return displayName; }
}