package f24launcher.content;

import f24launcher.core.LauncherPaths;
import f24launcher.util.DownloadManager;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.HashSet;
import java.util.Set;
import java.util.stream.Stream;

/**
 * Almacén de objetos por contenido (content-addressed) para deduplicar el
 * contenido de las instancias: un mod idéntico usado por varias instancias se
 * guarda una sola vez en {@code instances/.objects/<sha1>} y cada instancia lo
 * referencia con un <b>hardlink</b> (mismo inodo, no una copia).
 *
 * <p>El store vive junto a las instancias (mismo volumen) porque los hardlinks
 * exigen el mismo sistema de archivos. Si no se puede enlazar (otro volumen o FS
 * sin soporte), cae a una copia normal — siempre correcto, solo pierde el ahorro.
 *
 * <p>Activar/desactivar (renombrar a {@code .disabled}) y borrar funcionan igual:
 * renombrar/borrar un hardlink no afecta a los demás. Los objetos sin referencia
 * se reclaman con {@link #gc()}.
 */
public final class ObjectStore {

    private static final Logger log = LoggerFactory.getLogger(ObjectStore.class);

    private ObjectStore() {}

    private static Path storeDir() {
        Path dir = LauncherPaths.instances().resolve(".objects");
        try { Files.createDirectories(dir); } catch (Exception ignored) {}
        return dir;
    }

    private static Path objectPath(String sha1) {
        return storeDir().resolve(sha1.toLowerCase());
    }

    /**
     * Si el store ya contiene el objeto, coloca {@code dest} como hardlink a él
     * (sin descargar) y devuelve true. Si no está o no se puede enlazar, false.
     */
    public static boolean tryLink(Path dest, String sha1) {
        if (sha1 == null || sha1.isBlank()) return false;
        Path obj = objectPath(sha1);
        if (!Files.exists(obj)) return false;
        try {
            Files.createDirectories(dest.getParent());
            Files.deleteIfExists(dest);
            Files.createLink(dest, obj);
            return true;
        } catch (Exception e) {
            return false; // otro volumen / FS sin soporte → que se descargue normal
        }
    }

    /**
     * Committer del {@link DownloadManager} en modo streaming: el contenido ya está
     * en el temporal {@code tmp}. Si el objeto no está en el store, el temporal pasa a
     * SER el objeto (move, sin recopiar a memoria); si ya está, descarta el temporal.
     * En ambos casos deja {@code dest} como hardlink al objeto (cae a copia si no se
     * puede enlazar). No relee el archivo salvo para hashearlo si no se conoce el sha1.
     */
    public static void linkWriteFile(Path tmp, Path dest, String sha1) throws IOException {
        String key = (sha1 == null || sha1.isBlank()) ? DownloadManager.fileHash(tmp, "SHA-1") : sha1;
        if (key == null || key.isBlank()) {
            DownloadManager.moveAtomic(tmp, dest);
            return;
        }
        key = key.toLowerCase();
        Path obj = objectPath(key);
        if (!Files.exists(obj)) {
            try {
                DownloadManager.moveAtomic(tmp, obj); // el temporal se convierte en el objeto del store
            } catch (IOException e) {
                DownloadManager.moveAtomic(tmp, dest); // no se pudo poblar el store → directo a dest
                return;
            }
        } else {
            Files.deleteIfExists(tmp); // el objeto ya existe (dedup): el temporal sobra
        }
        try {
            Files.createDirectories(dest.getParent());
            Files.deleteIfExists(dest);
            Files.createLink(dest, obj);
        } catch (Exception e) {
            Files.createDirectories(dest.getParent());
            Files.copy(obj, dest, StandardCopyOption.REPLACE_EXISTING); // otro volumen / FS sin hardlinks
        }
    }

    /**
     * Recolecta objetos del store que ningún {@code content.json} referencia (por
     * sha1). Borrar un objeto del store no afecta a las instancias que lo enlazan
     * (el inodo sobrevive mientras quede algún hardlink).
     */
    public static void gc() {
        Path store = storeDir();
        Set<String> referenced = new HashSet<>();
        try (Stream<Path> dirs = Files.list(LauncherPaths.instancesData())) {
            for (Path d : dirs.filter(Files::isDirectory).toList()) {
                ContentManifest m = ContentManifest.load(d.getFileName().toString());
                for (ContentManifest.Entry e : m.items)
                    if (e.sha1 != null && !e.sha1.isBlank()) referenced.add(e.sha1.toLowerCase());
            }
        } catch (Exception ignored) {}
        int removed = 0;
        try (Stream<Path> objs = Files.list(store)) {
            for (Path o : objs.filter(Files::isRegularFile).toList()) {
                if (!referenced.contains(o.getFileName().toString().toLowerCase())) {
                    try { if (Files.deleteIfExists(o)) removed++; } catch (Exception ignored) {}
                }
            }
        } catch (Exception ignored) {}
        if (removed > 0) log.info("ObjectStore GC: {} objeto(s) sin referencia eliminados.", removed);
    }
}
