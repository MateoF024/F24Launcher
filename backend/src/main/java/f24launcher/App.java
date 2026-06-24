package f24launcher;

import f24launcher.content.ObjectStore;
import f24launcher.core.LauncherPaths;
import f24launcher.ipc.IpcServer;
import f24launcher.settings.AppSettings;
import f24launcher.util.AppExecutors;
import f24launcher.util.DownloadManager;
import f24launcher.util.HttpConnectionPool;
import f24launcher.util.LogManager;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.PrintStream;

/**
 * Punto de entrada del backend de F24Launcher.
 *
 * Es un servicio headless (sin GUI): arranca el servidor IPC local (Javalin)
 * en 127.0.0.1 con un puerto efímero y un token de sesión, e imprime el
 * handshake por stdout para que el shell Tauri lo capture.
 */
public class App {

    // Prepara el logging del backend antes de que se inicialice cualquier logger
    // (slf4j-simple lee las propiedades del sistema al arrancar): rota los logs de
    // la sesión anterior a un .zip y deja logs/backend-latest.log limpio.
    static {
        // Servicio sin GUI: AWT/ImageIO (normalización de iconos) en modo headless.
        System.setProperty("java.awt.headless", "true");
        LogManager.setup();
    }

    private static final Logger log = LoggerFactory.getLogger(App.class);

    public static void main(String[] args) {
        try {
            System.setOut(new PrintStream(System.out, true, "UTF-8"));
            System.setErr(new PrintStream(System.err, true, "UTF-8"));
        } catch (Exception e) {
            System.err.println("No se pudo configurar UTF-8: " + e.getMessage());
        }

        log.info("=== INICIANDO F24Launcher backend (headless) v{} ===", AppVersion.VERSION);
        log.info("Java: {} {} ({}) · OS: {} {}",
                System.getProperty("java.vendor"), System.getProperty("java.version"),
                System.getProperty("java.vm.name"), System.getProperty("os.name"),
                System.getProperty("os.arch"));
        log.info("Flags JVM: {}", java.lang.management.ManagementFactory.getRuntimeMXBean().getInputArguments());
        log.info("Memoria al arrancar: {}", LogManager.memoryInfo());
        log.info("Rutas: root={} · instances={} · runtimes={} · logs={}",
                LauncherPaths.root(), LauncherPaths.instances(), LauncherPaths.runtimes(), LogManager.logsDir());

        // Concurrencia de descargas/escrituras (modelo de reinicio: se lee al arrancar).
        AppSettings settings = AppSettings.getInstance();
        int maxDownloads = settings.getMaxConcurrentDownloads();
        DownloadManager.getInstance().configure(maxDownloads, settings.getMaxConcurrentWrites());
        // Dispatcher alineado al límite + caché de disco para metadatos (64 MB).
        HttpConnectionPool.getInstance().configure(maxDownloads, LauncherPaths.httpCache().toFile(), 64L * 1024 * 1024);
        log.info("Concurrencia: {} descargas / {} escrituras", maxDownloads, settings.getMaxConcurrentWrites());

        // Monitor de memoria periódico (clave para diagnosticar congelamientos por GC).
        startMemoryMonitor();

        IpcServer server = new IpcServer();
        int port;
        try {
            port = server.start();
        } catch (Exception e) {
            log.error("Error fatal al arrancar el servidor IPC: {}", e.getMessage(), e);
            System.exit(1);
            return;
        }

        // Handshake con el shell Tauri: clave=valor por stdout (una línea cada uno).
        System.out.println("F24LAUNCHER_PORT=" + port);
        System.out.println("F24LAUNCHER_TOKEN=" + server.getToken());
        System.out.flush();

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            server.stop();
            AppExecutors.shutdownAll();
        }));

        // Salida limpia ligada al shell: cuando Tauri cierra nuestro stdin (al salir o
        // si se cae), recibimos EOF → System.exit(0). Eso corre los shutdown hooks y
        // deja que AppCDS vuelque su archivo (con kill duro no se volcaría), y evita
        // backends huérfanos si el shell muere inesperadamente.
        Thread stdinWatcher = new Thread(() -> {
            try {
                while (System.in.read() != -1) { /* descarta cualquier dato */ }
            } catch (Exception ignored) {}
            log.info("stdin cerrado por el shell; cerrando backend limpiamente.");
            System.exit(0);
        }, "stdin-watcher");
        stdinWatcher.setDaemon(true);
        stdinWatcher.start();

        // Reclama objetos del store de dedup sin referencia (en segundo plano, no bloquea).
        AppExecutors.io().submit(ObjectStore::gc);

        log.info("F24Launcher backend escuchando en 127.0.0.1:{}", port);
    }

    /** Registra el uso de memoria cada 60 s para poder correlacionar congelamientos con el GC. */
    private static void startMemoryMonitor() {
        Thread t = new Thread(() -> {
            while (true) {
                try { Thread.sleep(60_000); } catch (InterruptedException e) { return; }
                log.info("Memoria: {}", LogManager.memoryInfo());
            }
        }, "mem-monitor");
        t.setDaemon(true);
        t.start();
    }
}
