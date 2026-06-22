package f24launcher.core.launch;

import f24launcher.auth.Account;
import f24launcher.core.LauncherPaths;
import f24launcher.core.loader.LoaderInstaller;
import f24launcher.core.meta.MojangMeta.VersionDetails;
import f24launcher.core.runtime.JavaRuntimeManager;
import f24launcher.instance.InstanceConfig;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Lanza Minecraft: resuelve la versión, extrae natives, construye el comando y
 * arranca el proceso, transmitiendo su salida línea a línea por el LogSink.
 */
public class GameLauncher {

    private static final Logger log = LoggerFactory.getLogger(GameLauncher.class);

    public interface LogSink {
        void onLine(String line);
        void onExit(int code);
    }

    private final LoaderInstaller loaders = new LoaderInstaller();
    private final NativesExtractor natives = new NativesExtractor();
    private final ArgumentBuilder args = new ArgumentBuilder();
    private final JavaRuntimeManager runtimes = new JavaRuntimeManager();
    private final Map<String, Process> running = new ConcurrentHashMap<>();

    public void launch(InstanceConfig cfg, Account account, LogSink sink) throws Exception {
        VersionDetails v = loaders.resolveVersion(cfg);
        natives.extract(cfg, v);

        int major = (v.javaVersion != null) ? v.javaVersion.majorVersion : 21;
        sink.onLine("[F24] Preparando runtime Java " + major + " (puede descargarse la primera vez)...");
        String javaExe = runtimes.resolveJavaExe(cfg, major);
        sink.onLine("[F24] Java: " + javaExe);
        List<String> cmd = args.build(cfg, v, account, javaExe);

        log.info("Lanzando {} (MC {}) con {}", cfg.id, cfg.mcVersion, javaExe);
        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.directory(LauncherPaths.instanceGameDir(cfg.id).toFile());
        pb.redirectErrorStream(true);

        Process p = pb.start();
        running.put(cfg.id, p);

        Thread reader = new Thread(() -> {
            try (BufferedReader r = new BufferedReader(
                    new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = r.readLine()) != null) sink.onLine(line);
            } catch (Exception ignored) {
            } finally {
                int code = -1;
                try { code = p.waitFor(); } catch (InterruptedException ignored) {}
                running.remove(cfg.id);
                sink.onExit(code);
            }
        }, "mc-log-" + cfg.id);
        reader.setDaemon(true);
        reader.start();
    }

    public boolean isRunning(String id) {
        Process p = running.get(id);
        return p != null && p.isAlive();
    }

    public void stop(String id) {
        Process p = running.get(id);
        if (p != null) p.destroy();
    }
}
