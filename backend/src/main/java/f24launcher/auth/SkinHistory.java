package f24launcher.auth;

import f24launcher.core.LauncherPaths;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;

import java.lang.reflect.Type;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Historial local de skins aplicadas por cuenta, en
 * <code>%APPDATA%\F24Launcher\skins\&lt;uuid&gt;.json</code>. Mojang no expone un
 * historial de skins por API, así que el launcher lo guarda localmente (igual que
 * el launcher oficial) para poder reaplicar skins usadas recientemente.
 */
public class SkinHistory {

    private static final Gson GSON = new Gson();
    private static final Type LIST = new TypeToken<List<Entry>>() {}.getType();
    private static final int MAX = 24;

    public static class Entry {
        public String url;
        public String variant;
        public long ts;
    }

    private Path file(String uuid) {
        return LauncherPaths.root().resolve("skins").resolve(safe(uuid) + ".json");
    }

    private static String safe(String s) {
        return (s == null ? "x" : s.replaceAll("[^a-zA-Z0-9_-]", "_"));
    }

    public synchronized List<Entry> list(String uuid) {
        try {
            Path f = file(uuid);
            if (Files.exists(f) && Files.size(f) > 0) {
                List<Entry> l = GSON.fromJson(Files.readString(f), LIST);
                if (l != null) return l;
            }
        } catch (Exception ignored) {}
        return new ArrayList<>();
    }

    public synchronized void record(String uuid, String url, String variant) {
        if (url == null || url.isBlank()) return;
        List<Entry> l = list(uuid);
        l.removeIf(e -> url.equals(e.url)); // si ya estaba, se mueve al frente
        Entry e = new Entry();
        e.url = url;
        e.variant = "slim".equalsIgnoreCase(variant) ? "slim" : "classic";
        e.ts = System.currentTimeMillis();
        l.add(0, e);
        while (l.size() > MAX) l.remove(l.size() - 1);
        try {
            Path f = file(uuid);
            Files.createDirectories(f.getParent());
            Files.writeString(f, GSON.toJson(l, LIST));
        } catch (Exception ignored) {}
    }
}
