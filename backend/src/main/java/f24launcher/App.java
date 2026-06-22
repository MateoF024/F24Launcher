package f24launcher;

import f24launcher.ipc.IpcServer;
import f24launcher.util.AppExecutors;

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

    // Redirige el log del backend a un archivo en el directorio de datos, antes
    // de que se inicialice cualquier logger (slf4j-simple lee esto al arrancar).
    static {
        if (System.getProperty("org.slf4j.simpleLogger.logFile") == null) {
            try {
                java.nio.file.Path dir = java.nio.file.Paths.get(
                        System.getProperty("user.home"), "AppData", "Roaming", "F24Launcher");
                java.nio.file.Files.createDirectories(dir);
                System.setProperty("org.slf4j.simpleLogger.logFile",
                        dir.resolve("backend.log").toString());
            } catch (Exception ignored) {}
        }
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
        log.info("Java: {} · OS: {}", System.getProperty("java.version"), System.getProperty("os.name"));

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

        log.info("F24Launcher backend escuchando en 127.0.0.1:{}", port);
    }
}
