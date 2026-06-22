package f24launcher.content;

/**
 * Categoría de contenido instalable en una instancia y su mapeo a cada API y a
 * la carpeta de .minecraft donde se deposita.
 */
public enum ContentType {
    MODS("mods", "mod", 6, "mods"),
    RESOURCEPACKS("resourcepacks", "resourcepack", 12, "resourcepacks"),
    SHADERS("shaders", "shader", 6552, "shaderpacks"),
    DATAPACKS("datapacks", "datapack", 6945, "datapacks");

    public final String id;        // clave usada en la API IPC
    public final String modrinth;  // project_type de Modrinth
    public final int cfClassId;    // classId de CurseForge (-1 = no soportado)
    public final String folder;    // subcarpeta dentro de .minecraft

    ContentType(String id, String modrinth, int cfClassId, String folder) {
        this.id = id;
        this.modrinth = modrinth;
        this.cfClassId = cfClassId;
        this.folder = folder;
    }

    public boolean usesLoader() { return this == MODS; }

    public static ContentType from(String id) {
        for (ContentType t : values()) if (t.id.equals(id)) return t;
        return MODS;
    }
}
