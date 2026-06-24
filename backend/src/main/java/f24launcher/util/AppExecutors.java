package f24launcher.util;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * Executor de tareas de fondo del backend (instalaciones, lanzamientos, reparación,
 * GC del store…).
 *
 * <p>Usa <b>virtual threads</b> (Java 21): cada tarea corre en su propio hilo virtual,
 * así varias instalaciones pueden ir en paralelo sin un cap fijo de hilos de
 * plataforma (antes era un pool fijo de 4 que serializaba/colgaba las instalaciones
 * concurrentes). La concurrencia <i>real</i> de red/disco la siguen acotando los
 * semáforos del {@link DownloadManager}, no este executor.
 */
public final class AppExecutors {

    private static final ExecutorService IO = Executors.newVirtualThreadPerTaskExecutor();

    private AppExecutors() {}

    public static ExecutorService io() { return IO; }

    public static void shutdownAll() {
        IO.shutdown();
        try {
            IO.awaitTermination(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
