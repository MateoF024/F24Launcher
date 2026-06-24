package f24launcher.util;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import okhttp3.Response;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Núcleo unificado de descargas a disco (Fase A de la 0.0.3).
 *
 * Todas las descargas de archivos del launcher (librerías, assets, mods, modpacks,
 * reparación…) pasan por aquí. Aporta, en un único sitio:
 *
 * <ul>
 *   <li><b>Concurrencia configurable y separada</b>: un semáforo limita las
 *       descargas simultáneas (red) y otro las escrituras simultáneas (disco),
 *       igual que Modrinth. Así el usuario puede bajarlas en conexiones o discos
 *       lentos. Los valores se fijan al arrancar (ver {@link #configure}).</li>
 *   <li><b>Escritura atómica</b>: cada archivo se baja a un temporal {@code .part}
 *       en la carpeta de destino y se mueve con {@code ATOMIC_MOVE}; nunca queda un
 *       archivo a medias.</li>
 *   <li><b>Bloqueo por ruta</b>: dos tareas nunca escriben el mismo destino a la
 *       vez ("no se pisen").</li>
 *   <li><b>Verificación de integridad</b>: si se conoce el sha1/sha512, se
 *       comprueba lo descargado y se reintenta si no cuadra.</li>
 *   <li><b>Saltar si ya está</b>: si el destino existe y es válido (por hash o por
 *       tamaño), no se vuelve a descargar.</li>
 * </ul>
 *
 * <p>Implementación: cada tarea corre en un <i>virtual thread</i> (Java 21) y los
 * semáforos marcan la concurrencia real. La descarga es <b>en streaming a disco</b>
 * (a un temporal {@code .part}, con un buffer fijo de 64 KB y el hash calculado al
 * vuelo): la memoria por descarga es constante, no proporcional al tamaño del
 * archivo, así que ni los modpacks grandes ni el JRE disparan la RAM.
 */
public final class DownloadManager {

    private static final Logger log = LoggerFactory.getLogger(DownloadManager.class);

    /** Reintentos cuando el hash de lo descargado no coincide con el esperado. */
    private static final int HASH_RETRIES = 2;

    private static DownloadManager instance;

    private volatile Semaphore downloadPermits;
    private volatile Semaphore writePermits;

    // Un cerrojo (con conteo de referencias) por ruta de destino normalizada → serializa
    // las escrituras al mismo archivo, retirándolo del mapa solo cuando ya nadie lo usa.
    private final ConcurrentHashMap<Path, LockRef> pathLocks = new ConcurrentHashMap<>();

    /** Cerrojo por ruta con conteo de referencias (se elimina del mapa al llegar a 0). */
    private static final class LockRef {
        final ReentrantLock lock = new ReentrantLock();
        int refs;
    }

    private final HttpConnectionPool http = HttpConnectionPool.getInstance();

    private DownloadManager() {
        int[] def = defaultLimits();
        this.downloadPermits = new Semaphore(def[0]);
        this.writePermits = new Semaphore(def[1]);
    }

    public static synchronized DownloadManager getInstance() {
        if (instance == null) instance = new DownloadManager();
        return instance;
    }

    /**
     * Valores por defecto: descargas = 8 (la red no depende de la CPU); escrituras =
     * núcleos acotado a [4, 16] (el disco + el hashing sí dependen algo de la CPU).
     */
    public static int[] defaultLimits() {
        int cores = Runtime.getRuntime().availableProcessors();
        int writes = Math.max(4, Math.min(16, cores));
        return new int[]{8, writes};
    }

    /**
     * Fija los límites de concurrencia (los llamará la Fase B desde los ajustes, al
     * arrancar). 0 = usar el valor por defecto. No debe llamarse con descargas en curso.
     */
    public synchronized void configure(int maxDownloads, int maxWrites) {
        int[] def = defaultLimits();
        int d = maxDownloads > 0 ? maxDownloads : def[0];
        int w = maxWrites > 0 ? maxWrites : def[1];
        this.downloadPermits = new Semaphore(d);
        this.writePermits = new Semaphore(w);
        log.info("DownloadManager configurado: {} descargas / {} escrituras concurrentes", d, w);
    }

    // ── API ───────────────────────────────────────────────────────────

    public enum Status { DOWNLOADED, SKIPPED, FAILED }

    /** Una descarga: URL de origen, ruta de destino, y (opcional) tamaño/hashes esperados. */
    public record Task(String url, Path dest, long expectedSize, String sha1, String sha512) {}

    public record Result(Task task, Status status, Exception error) {
        public boolean ok() { return status != Status.FAILED; }
    }

    /** Callback de progreso agregado (archivos completados / total). */
    public interface Progress { void update(long done, long total); }

    /**
     * Estrategia para colocar en su sitio el archivo ya descargado (en el temporal
     * {@code tempFile}). Por defecto es un move atómico; el dedup (store + hardlinks)
     * inyecta la suya. Opera sobre rutas, no sobre {@code byte[]}: nunca relee el
     * archivo a memoria.
     */
    public interface Committer { void commit(Path tempFile, Path dest, String sha1) throws IOException; }

    private static final Progress NOOP = (d, t) -> {};
    private static final Committer ATOMIC = (tmp, dest, sha1) -> moveAtomic(tmp, dest);

    /** Descarga una sola tarea (bloqueante) con escritura atómica. */
    public Result download(Task task, boolean verifyExisting) {
        return runOne(task, verifyExisting, ATOMIC);
    }

    /** Descarga una sola tarea con una estrategia de escritura concreta. */
    public Result download(Task task, boolean verifyExisting, Committer committer) {
        return runOne(task, verifyExisting, committer);
    }

    /** Descarga todas las tareas con escritura atómica. */
    public List<Result> downloadAll(List<Task> tasks, Progress progress, boolean verifyExisting) {
        return downloadAll(tasks, progress, verifyExisting, ATOMIC);
    }

    /**
     * Descarga todas las tareas respetando los límites de concurrencia y devuelve un
     * resultado por cada una. No lanza por fallos individuales; usa
     * {@link #requireAllOk(List)} si quieres abortar ante el primer fallo.
     *
     * @param verifyExisting si true, valida por hash los archivos ya presentes
     *                       (modo reparación); si false, ruta rápida (salta por
     *                       tamaño o por existencia).
     * @param committer      estrategia de escritura (atómica o dedup).
     */
    public List<Result> downloadAll(List<Task> tasks, Progress progress, boolean verifyExisting, Committer committer) {
        Progress p = progress != null ? progress : NOOP;
        long total = tasks.size();
        List<Result> results = new ArrayList<>(tasks.size());
        if (total == 0) { p.update(0, 0); return results; }

        long start = System.nanoTime();
        AtomicLong done = new AtomicLong();
        long step = Math.max(1, total / 200); // limita la frecuencia de eventos de progreso
        p.update(0, total);

        try (ExecutorService exec = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<?>> futures = new ArrayList<>(tasks.size());
            for (Task t : tasks) {
                futures.add(exec.submit(() -> {
                    Result r = runOne(t, verifyExisting, committer);
                    synchronized (results) { results.add(r); }
                    long d = done.incrementAndGet();
                    if (d % step == 0 || d == total) p.update(d, total);
                }));
            }
            for (Future<?> f : futures) {
                try {
                    f.get();
                } catch (InterruptedException ie) {
                    // Cancelación: interrumpe el resto de descargas y aborta.
                    Thread.currentThread().interrupt();
                    for (Future<?> rest : futures) rest.cancel(true);
                    break;
                } catch (Exception ignored) { /* el error ya está en el Result */ }
            }
        }
        int dl = 0, sk = 0, fa = 0;
        for (Result r : results) switch (r.status()) {
            case DOWNLOADED -> dl++;
            case SKIPPED -> sk++;
            case FAILED -> fa++;
        }
        log.info("Lote de descargas: {} descargadas · {} en caché · {} fallidas de {} ({} ms)",
                dl, sk, fa, total, (System.nanoTime() - start) / 1_000_000);
        p.update(total, total);
        return results;
    }

    /** Lanza una IOException si alguna descarga falló (para instaladores que deben abortar). */
    public static void requireAllOk(List<Result> results) throws IOException {
        for (Result r : results) {
            if (r.status() == Status.FAILED) {
                throw new IOException("Fallo descargando " + r.task().url(),
                        r.error());
            }
        }
    }

    // ── Núcleo de una tarea ───────────────────────────────────────────

    private Result runOne(Task t, boolean verifyExisting, Committer committer) {
        try {
            downloadPermits.acquire();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new Result(t, Status.FAILED, e);
        }
        // Mantenemos el permiso de descarga durante toda la tarea para acotar a
        // maxDownloads las descargas (en streaming a disco) en vuelo a la vez.
        Path tmp = null;
        try {
            if (isAlreadyValid(t, verifyExisting)) {
                return new Result(t, Status.SKIPPED, null);
            }
            tmp = tempFor(t.dest());
            String sha1 = fetchToTemp(t, tmp);
            commit(tmp, t.dest(), sha1, committer);
            tmp = null; // el committer movió/enlazó el temporal: ya no hay que borrarlo
            return new Result(t, Status.DOWNLOADED, null);
        } catch (Throwable e) {
            // Throwable: un Error (p. ej. al quedarnos sin disco/memoria) debe
            // convertirse en un Result FAILED, no matar el hilo virtual.
            Exception err = (e instanceof Exception ex) ? ex : new RuntimeException(e);
            log.warn("No se pudo descargar {}: {}", t.url(), err.getMessage());
            return new Result(t, Status.FAILED, err);
        } finally {
            if (tmp != null) {
                try { Files.deleteIfExists(tmp); } catch (Exception ignored) {}
            }
            downloadPermits.release();
        }
    }

    /** ¿El destino ya existe y es válido? Evita re-descargar. */
    private boolean isAlreadyValid(Task t, boolean verifyExisting) throws IOException {
        Path dest = t.dest();
        if (!Files.exists(dest)) return false;
        if (verifyExisting) {
            // Modo reparación: exige coincidencia de hash (o de tamaño si no hay hash).
            if (t.sha1() != null && !t.sha1().isBlank()) return t.sha1().equalsIgnoreCase(fileHash(dest, "SHA-1"));
            if (t.sha512() != null && !t.sha512().isBlank()) return t.sha512().equalsIgnoreCase(fileHash(dest, "SHA-512"));
            if (t.expectedSize() > 0) return Files.size(dest) == t.expectedSize();
            return true;
        }
        // Ruta rápida: el tamaño es una comprobación barata; si no se conoce, basta
        // con que exista (idempotencia, como hacía VanillaInstaller).
        if (t.expectedSize() > 0) return Files.size(dest) == t.expectedSize();
        if (t.sha1() != null && !t.sha1().isBlank()) return t.sha1().equalsIgnoreCase(fileHash(dest, "SHA-1"));
        return true;
    }

    /** Descarga por streaming a {@code tmp}, verifica el hash y reintenta; devuelve el sha1. */
    private String fetchToTemp(Task t, Path tmp) throws IOException {
        IOException last = null;
        for (int attempt = 0; attempt <= HASH_RETRIES; attempt++) {
            String sha1 = streamToFile(t.url(), tmp);
            if (hashOk(t, tmp, sha1)) return sha1;
            last = new IOException("Hash no coincide para " + t.url());
            log.debug("Hash no coincide ({}), reintento {}/{}", t.url(), attempt + 1, HASH_RETRIES);
            Files.deleteIfExists(tmp);
        }
        throw last != null ? last : new IOException("Descarga fallida: " + t.url());
    }

    /**
     * Descarga {@code url} a {@code tmp} por streaming (buffer de 64 KB, sin cargar el
     * archivo entero en memoria) y devuelve el SHA-1 hex de lo escrito.
     */
    private String streamToFile(String url, Path tmp) throws IOException {
        Path parent = tmp.getParent();
        if (parent != null) Files.createDirectories(parent);
        MessageDigest md;
        try {
            md = MessageDigest.getInstance("SHA-1");
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IOException("SHA-1 no disponible", e);
        }
        try (Response resp = http.getRaw(url);
             InputStream in = new DigestInputStream(resp.body().byteStream(), md);
             OutputStream out = Files.newOutputStream(tmp, StandardOpenOption.CREATE,
                     StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE)) {
            byte[] buf = new byte[65536];
            int r;
            while ((r = in.read(buf)) > 0) out.write(buf, 0, r);
        }
        return hex(md.digest());
    }

    /** ¿Lo descargado coincide con el hash esperado de la tarea? (sha1 ya calculado al vuelo). */
    private boolean hashOk(Task t, Path file, String sha1) throws IOException {
        if (t.sha1() != null && !t.sha1().isBlank()) return t.sha1().equalsIgnoreCase(sha1);
        if (t.sha512() != null && !t.sha512().isBlank()) return t.sha512().equalsIgnoreCase(fileHash(file, "SHA-512"));
        return true; // sin hash conocido, aceptamos lo descargado
    }

    /** Aplica el committer bajo el límite de escrituras y el cerrojo de la ruta de destino. */
    private void commit(Path tmp, Path dest, String sha1, Committer committer) throws IOException {
        Path key = dest.toAbsolutePath().normalize();
        // Toma una referencia del cerrojo de la ruta ANTES de competir por él: así nadie
        // puede retirarlo del mapa mientras este hilo espera (la carrera del esquema viejo).
        LockRef ref = pathLocks.compute(key, (k, v) -> {
            if (v == null) v = new LockRef();
            v.refs++;
            return v;
        });
        boolean permit = false;
        try {
            writePermits.acquire();
            permit = true;
            ref.lock.lock();
            try {
                committer.commit(tmp, dest, sha1);
            } finally {
                ref.lock.unlock();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrumpido al escribir " + dest, e);
        } finally {
            if (permit) writePermits.release();
            // Suelta la referencia; retira la entrada del mapa solo cuando llega a 0.
            pathLocks.compute(key, (k, v) -> (v == null || --v.refs <= 0) ? null : v);
        }
    }

    /** Mueve {@code tmp} a {@code dest} de forma atómica (deben estar en el mismo volumen). */
    public static void moveAtomic(Path tmp, Path dest) throws IOException {
        Path parent = dest.getParent();
        if (parent != null) Files.createDirectories(parent);
        try {
            Files.move(tmp, dest, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException | FileAlreadyExistsException e) {
            // El destino ya existe o el FS no soporta move atómico con reemplazo:
            // reemplazo no atómico (seguro porque estamos dentro del cerrojo de ruta).
            Files.move(tmp, dest, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    /** Ruta de un temporal {@code .part} hermano del destino (mismo volumen → move barato). */
    public static Path tempFor(Path dest) {
        return dest.resolveSibling(dest.getFileName() + "." + UUID.randomUUID() + ".part");
    }

    /** Escribe {@code data} en {@code dest} de forma atómica (temporal + move). Sin lock ni semáforo. */
    public static void writeAtomic(Path dest, byte[] data) throws IOException {
        Path parent = dest.getParent();
        if (parent != null) Files.createDirectories(parent);
        Path tmp = dest.resolveSibling(dest.getFileName() + "." + UUID.randomUUID() + ".part");
        try {
            Files.write(tmp, data);
            try {
                Files.move(tmp, dest, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException | FileAlreadyExistsException e) {
                // Algunos sistemas no permiten move atómico con reemplazo: caemos a
                // reemplazo no atómico (seguro porque estamos dentro del cerrojo de ruta).
                Files.move(tmp, dest, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(tmp);
        }
    }

    // ── Hashing ───────────────────────────────────────────────────────

    /** Hash hexadecimal de un buffer en memoria. */
    public static String bytesHash(byte[] data, String algorithm) {
        try {
            return hex(MessageDigest.getInstance(algorithm).digest(data));
        } catch (Exception e) {
            return "";
        }
    }

    /** Hash hexadecimal de un archivo, leído por bloques (sin cargarlo entero en memoria). */
    public static String fileHash(Path file, String algorithm) throws IOException {
        try {
            MessageDigest md = MessageDigest.getInstance(algorithm);
            try (InputStream in = Files.newInputStream(file)) {
                byte[] buf = new byte[65536];
                int r;
                while ((r = in.read(buf)) > 0) md.update(buf, 0, r);
            }
            return hex(md.digest());
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IOException("Algoritmo no disponible: " + algorithm, e);
        }
    }

    private static String hex(byte[] h) {
        StringBuilder sb = new StringBuilder(h.length * 2);
        for (byte b : h) sb.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
        return sb.toString();
    }
}
