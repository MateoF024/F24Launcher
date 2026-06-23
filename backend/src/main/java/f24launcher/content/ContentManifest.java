package f24launcher.content;

import f24launcher.core.LauncherPaths;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Registro persistente del contenido instalado en una instancia
 * (instances-data/&lt;id&gt;/content.json). Cada entrada vincula un archivo en disco
 * con el proyecto de Modrinth/CurseForge del que provino.
 */
public class ContentManifest {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    public static class Entry {
        public String fileName;     // nombre en disco (puede acabar en .disabled)
        public String type;
        public String source;
        public String projectId;
        public String slug;
        public String name;
        public String iconUrl;
        public String author;
        public String versionId;
        public String versionName;
        public List<String> categories;
        public String clientSide;
        public String serverSide;
        // Datos para exportar (mrpack/.f24pack) y verificar integridad. Se rellenan
        // al instalar desde el launcher; pueden faltar en mods añadidos a mano.
        public String downloadUrl;
        public String sha1;
        public String sha512;
        public long fileSize;
    }

    public List<Entry> items = new ArrayList<>();

    public static ContentManifest load(String instanceId) {
        Path file = path(instanceId);
        if (!Files.exists(file)) return new ContentManifest();
        try (Reader r = Files.newBufferedReader(file)) {
            ContentManifest m = GSON.fromJson(r, ContentManifest.class);
            if (m == null) return new ContentManifest();
            if (m.items == null) m.items = new ArrayList<>();
            return m;
        } catch (Exception e) {
            return new ContentManifest();
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

    public Entry find(String source, String projectId, String type) {
        for (Entry e : items)
            if (e.source.equals(source) && e.projectId.equals(projectId) && e.type.equals(type)) return e;
        return null;
    }

    public Entry findByFile(String fileName) {
        String base = base(fileName);
        for (Entry e : items) if (base(e.fileName).equals(base)) return e;
        return null;
    }

    public void remove(Entry e) { items.remove(e); }

    private static String base(String fileName) {
        return fileName.endsWith(".disabled") ? fileName.substring(0, fileName.length() - 9) : fileName;
    }

    private static Path path(String instanceId) {
        return LauncherPaths.instanceData(instanceId).resolve("content.json");
    }
}
