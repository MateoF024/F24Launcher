package f24launcher.modpack;

import f24launcher.core.LauncherPaths;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Registro de la versión de modpack instalada en una instancia
 * (instances-data/&lt;id&gt;/modpack.json). Lista qué archivos pertenecen al modpack
 * (ruta relativa al .minecraft + hash + tamaño) para poder calcular un diff fiable al
 * actualizar (Fase 4): añadir lo nuevo, reemplazar lo cambiado y quitar lo eliminado
 * sin tocar archivos del usuario (saves, options.txt, logs, etc.).
 *
 * <p>Cada entrada marca su origen: {@code DOWNLOADED} para los archivos de
 * {@code files[]} (mods descargados aparte) y {@code OVERRIDE} para lo extraído de los
 * overrides del pack (configs y demás). Esa distinción permite aplicar la regla
 * "solo las configs que el modpack cambia" en la actualización.</p>
 */
public class ModpackManifest {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    /** Origen de un archivo dentro del pack. */
    public enum Origin { DOWNLOADED, OVERRIDE }

    public static class Entry {
        public String path;      // ruta relativa al gameDir, con separador '/'
        public String sha1;       // hash del contenido ("" si no se conoce)
        public long size;
        public Origin origin = Origin.OVERRIDE;

        public Entry() {}

        public Entry(String path, String sha1, long size, Origin origin) {
            this.path = path;
            this.sha1 = sha1 != null ? sha1 : "";
            this.size = size;
            this.origin = origin != null ? origin : Origin.OVERRIDE;
        }
    }

    public String modpackId = "";
    public String version = "";
    public String variant = "";        // "standard" | "lite"
    public String format = "";          // mrpack | curseforge | zip
    public List<Entry> files = new ArrayList<>();

    public static ModpackManifest load(String instanceId) {
        Path file = path(instanceId);
        if (!Files.exists(file)) return new ModpackManifest();
        try (Reader r = Files.newBufferedReader(file)) {
            ModpackManifest m = GSON.fromJson(r, ModpackManifest.class);
            if (m == null) return new ModpackManifest();
            if (m.files == null) m.files = new ArrayList<>();
            return m;
        } catch (Exception e) {
            return new ModpackManifest();
        }
    }

    public void save(String instanceId) {
        Path file = path(instanceId);
        try {
            Files.createDirectories(file.getParent());
            try (Writer w = Files.newBufferedWriter(file)) {
                GSON.toJson(this, w);
            }
        } catch (Exception ignored) {}
    }

    public static boolean delete(String instanceId) {
        try {
            return Files.deleteIfExists(path(instanceId));
        } catch (Exception e) {
            return false;
        }
    }

    /** Índice ruta → entrada (la ruta es la clave canónica del modpack). */
    public Map<String, Entry> byPath() {
        Map<String, Entry> m = new LinkedHashMap<>();
        for (Entry e : files) if (e.path != null) m.put(e.path, e);
        return m;
    }

    private static Path path(String instanceId) {
        return LauncherPaths.instanceData(instanceId).resolve("modpack.json");
    }
}
