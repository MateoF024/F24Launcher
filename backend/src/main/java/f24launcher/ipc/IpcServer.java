package f24launcher.ipc;

import f24launcher.auth.Account;
import f24launcher.auth.AccountService;
import f24launcher.auth.MicrosoftAuth.AuthException;
import f24launcher.auth.StoredAccount;
import f24launcher.content.ContentInstaller;
import f24launcher.content.ContentModels.InstalledItem;
import f24launcher.content.ContentService;
import f24launcher.content.ContentType;
import f24launcher.core.LauncherPaths;
import f24launcher.core.launch.GameLauncher;
import f24launcher.core.loader.LoaderInstaller;
import f24launcher.core.meta.MojangMeta;
import f24launcher.core.meta.MojangMeta.VersionDetails;
import f24launcher.core.runtime.JavaRuntimeManager;
import f24launcher.core.version.VanillaInstaller;
import f24launcher.instance.IconStore;
import f24launcher.instance.InstanceConfig;
import f24launcher.instance.InstanceManager;
import f24launcher.modpack.ModpackExporter;
import f24launcher.modpack.ModpackInstaller;
import f24launcher.modpack.ModpackManifest;
import f24launcher.modpack.ModpackModels.Modpack;
import f24launcher.modpack.ModpackService;
import f24launcher.modpack.ModpackUpdater;
import f24launcher.settings.AppSettings;
import f24launcher.util.AppExecutors;
import f24launcher.util.HttpConnectionPool;
import f24launcher.util.LogManager;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import io.javalin.Javalin;
import io.javalin.http.HandlerType;
import io.javalin.http.UnauthorizedResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Future;
import java.util.stream.Stream;

/**
 * Servidor IPC local del backend (127.0.0.1, puerto efímero, token de sesión).
 * Expone los comandos del launcher por HTTP y el canal WebSocket /events.
 */
public class IpcServer {

    private static final Logger log = LoggerFactory.getLogger(IpcServer.class);
    public static final String TOKEN_HEADER = "X-F24-Token";

    private final String token = UUID.randomUUID().toString().replace("-", "");
    private final Gson gson = new GsonBuilder().disableHtmlEscaping().create();

    private final InstanceManager instanceManager = new InstanceManager();
    private final VanillaInstaller vanilla = new VanillaInstaller();
    private final LoaderInstaller loaders = new LoaderInstaller();
    private final GameLauncher gameLauncher = new GameLauncher();
    private final JavaRuntimeManager runtimes = new JavaRuntimeManager();
    private final AccountService accounts = new AccountService();
    private final ContentService content = new ContentService();
    private final ContentInstaller contentInstaller = new ContentInstaller(content);
    private final ModpackService modpacks = new ModpackService();
    private final ModpackInstaller modpackInstaller = new ModpackInstaller();
    private final EventBus eventBus = new EventBus();

    // Instalaciones en curso (para cancelarlas). cancelled marca las que el usuario abortó.
    private final Map<String, Future<?>> installs = new ConcurrentHashMap<>();
    private final Set<String> cancelled = ConcurrentHashMap.newKeySet();

    private Javalin app;

    public int start() {
        // Reconcilia instancias que quedaron marcadas como instaladas pero sin base jugable.
        instanceManager.reconcileInstalledStates();

        app = Javalin.create(cfg -> {
            cfg.showJavalinBanner = false;
            // Handlers en virtual threads (Java 21): la I/O bloqueante (búsquedas,
            // identify, updates, descarga del modpack…) ya no agota el pool de Jetty,
            // así que una operación lenta no congela el resto de la app.
            cfg.useVirtualThreads = true;
            cfg.bundledPlugins.enableCors(cors -> cors.addRule(rule -> rule.anyHost()));
        });

        // Marca de tiempo para medir la latencia de cada petición (se loguea en after).
        app.before(ctx -> ctx.attribute("t0", System.nanoTime()));

        app.before(ctx -> {
            if (ctx.method() == HandlerType.OPTIONS || "/health".equals(ctx.path())) return;
            // El WebSocket no puede enviar cabeceras custom → token por query-param.
            if ("/events".equals(ctx.path())) {
                if (!token.equals(ctx.queryParam("token"))) {
                    throw new UnauthorizedResponse("token inválido o ausente");
                }
                return;
            }
            // Recursos servidos directamente a <img> (no pueden enviar cabecera): token por query-param.
            if (ctx.method() == HandlerType.GET && ctx.path().endsWith("/icon")) {
                if (!token.equals(ctx.queryParam("token"))) {
                    throw new UnauthorizedResponse("token inválido o ausente");
                }
                return;
            }
            String provided = ctx.header(TOKEN_HEADER);
            if (provided == null || !provided.equals(token)) {
                throw new UnauthorizedResponse("token inválido o ausente");
            }
        });

        registerRoutes();

        // Log de cada petición con método, estado y latencia.
        app.after(ctx -> {
            Long t0 = ctx.attribute("t0");
            long ms = t0 != null ? (System.nanoTime() - t0) / 1_000_000 : -1;
            log.info("← {} {} {} ({} ms)", ctx.method(), ctx.path(), ctx.status().getCode(), ms);
        });

        // Manejador global: ningún error de handler queda sin registrar.
        app.exception(Exception.class, (e, ctx) -> {
            if (e instanceof io.javalin.http.HttpResponseException hre) {
                // Respuestas HTTP intencionadas (401/404/…): se respetan, no se "ascienden" a 500.
                ctx.status(hre.getStatus()).result(hre.getMessage());
                return;
            }
            log.error("Excepción no capturada en {} {}: {}", ctx.method(), ctx.path(), e.getMessage(), e);
            ctx.status(500).result("error interno");
        });

        app.start("127.0.0.1", 0);
        return app.port();
    }

    private void registerRoutes() {
        app.get("/health", ctx -> ctx.result("ok"));

        // ── Instancias ──
        app.get("/instances", ctx -> json(ctx, instanceManager.list()));

        app.get("/instances/{id}", ctx -> {
            InstanceConfig cfg = instanceManager.get(ctx.pathParam("id"));
            if (cfg == null) { ctx.status(404).result("no existe"); return; }
            json(ctx, cfg);
        });

        app.post("/instances", ctx -> {
            CreateReq req = gson.fromJson(ctx.body(), CreateReq.class);
            InstanceConfig cfg = instanceManager.create(req.name, req.mcVersion, req.loader, req.loaderVersion);
            // Opciones avanzadas opcionales del diálogo de creación.
            if (req.minMemoryMb != null) cfg.minMemoryMb = clamp(req.minMemoryMb, 256, 65536);
            if (req.maxMemoryMb != null) cfg.maxMemoryMb = clamp(req.maxMemoryMb, 512, 65536);
            if (cfg.maxMemoryMb < cfg.minMemoryMb) cfg.maxMemoryMb = cfg.minMemoryMb;
            if (req.windowWidth != null) cfg.windowWidth = Math.max(640, req.windowWidth);
            if (req.windowHeight != null) cfg.windowHeight = Math.max(480, req.windowHeight);
            if (req.fullscreen != null) cfg.fullscreen = req.fullscreen;
            if (req.jvmArgs != null) cfg.jvmArgs = req.jvmArgs.trim();
            if (req.javaPathOverride != null) cfg.javaPathOverride = req.javaPathOverride.trim();
            if (req.iconData != null && !req.iconData.isBlank() && IconStore.save(cfg.id, req.iconData)) {
                cfg.icon = "icon.png";
            }
            instanceManager.save(cfg);
            json(ctx, cfg);
        });

        // Edita ajustes de la instancia (memoria, args JVM, resolución, JRE…).
        app.patch("/instances/{id}", ctx -> {
            InstanceConfig cfg = instanceManager.get(ctx.pathParam("id"));
            if (cfg == null) { ctx.status(404).result("no existe"); return; }
            UpdateReq r = gson.fromJson(ctx.body(), UpdateReq.class);
            if (r != null) {
                if (r.name != null && !r.name.isBlank()) cfg.name = r.name.trim();
                if (r.minMemoryMb != null) cfg.minMemoryMb = clamp(r.minMemoryMb, 256, 65536);
                if (r.maxMemoryMb != null) cfg.maxMemoryMb = clamp(r.maxMemoryMb, 512, 65536);
                if (cfg.maxMemoryMb < cfg.minMemoryMb) cfg.maxMemoryMb = cfg.minMemoryMb;
                if (r.windowWidth != null) cfg.windowWidth = Math.max(640, r.windowWidth);
                if (r.windowHeight != null) cfg.windowHeight = Math.max(480, r.windowHeight);
                if (r.fullscreen != null) cfg.fullscreen = r.fullscreen;
                if (r.jvmArgs != null) cfg.jvmArgs = r.jvmArgs.trim();
                if (r.javaPathOverride != null) cfg.javaPathOverride = r.javaPathOverride.trim();
                // iconData: null = sin cambios · "" = quitar · base64 = establecer.
                if (r.iconData != null) {
                    if (r.iconData.isBlank()) { IconStore.delete(cfg.id); cfg.icon = ""; }
                    else if (IconStore.save(cfg.id, r.iconData)) cfg.icon = "icon.png";
                }
                if (r.favorite != null) cfg.favorite = r.favorite;
                if (r.group != null) cfg.group = r.group.trim();
            }
            instanceManager.save(cfg);
            json(ctx, cfg);
        });

        app.delete("/instances/{id}", ctx -> {
            boolean ok = instanceManager.delete(ctx.pathParam("id"));
            ctx.status(ok ? 204 : 404);
        });

        // Duplica una instancia (config + .minecraft + contenido).
        app.post("/instances/{id}/duplicate", ctx -> {
            InstanceConfig copy = instanceManager.duplicate(ctx.pathParam("id"));
            if (copy == null) { ctx.status(404).result("no se pudo duplicar"); return; }
            json(ctx, copy);
        });

        // Instala (descarga) la versión de la instancia. Progreso por WS /events.
        app.post("/instances/{id}/install", ctx -> {
            String id = ctx.pathParam("id");
            InstanceConfig cfg = instanceManager.get(id);
            if (cfg == null) { ctx.status(404).result("no existe"); return; }
            submitInstall(cfg.id, () -> runInstall(cfg));
            ctx.status(202).result("instalando");
        });

        // Lanza la instancia en modo offline. Logs por WS /events.
        app.post("/instances/{id}/launch", ctx -> {
            String id = ctx.pathParam("id");
            InstanceConfig cfg = instanceManager.get(id);
            if (cfg == null) { ctx.status(404).result("no existe"); return; }
            if (!cfg.installed) { ctx.status(409).result("la instancia no está instalada"); return; }
            if (gameLauncher.isRunning(id)) { ctx.status(409).result("ya está en ejecución"); return; }

            LaunchReq req = ctx.body().isBlank() ? new LaunchReq(null) : gson.fromJson(ctx.body(), LaunchReq.class);
            String offlineName = (req == null) ? null : req.username;
            AppExecutors.io().submit(() -> {
                Account account;
                try {
                    account = accounts.resolveLaunchAccount(offlineName);
                } catch (AuthException e) {
                    log.error("Auth al lanzar {}: {}", cfg.id, e.getMessage());
                    eventBus.publish("state", stateEvent(cfg.id, "error", e.getMessage()));
                    return;
                }
                runLaunch(cfg, account);
            });
            ctx.status(202).result("lanzando");
        });

        app.post("/instances/{id}/stop", ctx -> {
            gameLauncher.stop(ctx.pathParam("id"));
            ctx.status(204);
        });

        // Cancela una instalación en curso (interrumpe descargas; estado → stopped).
        app.post("/instances/{id}/cancel", ctx -> {
            String id = ctx.pathParam("id");
            Future<?> f = installs.get(id);
            if (f != null) { cancelled.add(id); f.cancel(true); }
            ctx.status(204);
        });

        // Lista los archivos de log/crash de la instancia (para el selector de la consola).
        app.get("/instances/{id}/logs", ctx -> {
            String id = ctx.pathParam("id");
            if (instanceManager.get(id) == null) { ctx.status(404).result("no existe"); return; }
            Path base = LauncherPaths.instanceGameDir(id);
            List<Map<String, Object>> out = new ArrayList<>();
            for (String sub : new String[]{"logs", "crash-reports"}) {
                Path dir = base.resolve(sub);
                if (!Files.isDirectory(dir)) continue;
                try (Stream<Path> files = Files.list(dir)) {
                    files.filter(Files::isRegularFile)
                         .filter(p -> { String n = p.getFileName().toString().toLowerCase();
                                        return n.endsWith(".log") || n.endsWith(".txt"); })
                         .forEach(p -> {
                            Map<String, Object> m = new LinkedHashMap<>();
                            m.put("name", p.getFileName().toString());
                            m.put("path", base.relativize(p).toString().replace('\\', '/'));
                            m.put("crash", "crash-reports".equals(sub));
                            m.put("mtime", mtime(p));
                            out.add(m);
                         });
                } catch (Exception ignored) {}
            }
            out.sort(Comparator.comparingLong((Map<String, Object> m) -> (Long) m.get("mtime")).reversed());
            json(ctx, out);
        });

        // Abre la carpeta de logs de la instancia en el explorador de Windows.
        app.post("/instances/{id}/logs/open", ctx -> {
            String id = ctx.pathParam("id");
            if (instanceManager.get(id) == null) { ctx.status(404).result("no existe"); return; }
            Path dir = LauncherPaths.instanceGameDir(id).resolve("logs");
            try {
                Files.createDirectories(dir);
                new ProcessBuilder("explorer.exe", dir.toString()).start();
                ctx.status(204);
            } catch (Exception e) {
                log.warn("No se pudo abrir los logs de {}: {}", id, e.getMessage());
                ctx.status(500).result("No se pudo abrir la carpeta de logs");
            }
        });

        // Devuelve las últimas líneas de un log de la instancia (para la consola).
        // ?file=<relativo> (por defecto logs/latest.log); validado dentro de la instancia.
        app.get("/instances/{id}/log", ctx -> {
            String id = ctx.pathParam("id");
            if (instanceManager.get(id) == null) { ctx.status(404).result("no existe"); return; }
            Path base = LauncherPaths.instanceGameDir(id);
            String rel = ctx.queryParam("file");
            Path logFile = (rel == null || rel.isBlank())
                    ? base.resolve("logs").resolve("latest.log")
                    : base.resolve(rel).normalize();
            if (!logFile.startsWith(base)) { ctx.status(400).result("ruta inválida"); return; }
            List<String> lines = new ArrayList<>();
            if (Files.exists(logFile)) {
                try {
                    byte[] data = Files.readAllBytes(logFile);
                    String[] arr = new String(data, StandardCharsets.UTF_8).split("\r?\n", -1);
                    int from = Math.max(0, arr.length - 5000);
                    for (int i = from; i < arr.length; i++) lines.add(arr[i]);
                    if (!lines.isEmpty() && lines.get(lines.size() - 1).isEmpty())
                        lines.remove(lines.size() - 1);
                } catch (Exception e) {
                    log.debug("No se pudo leer latest.log de {}: {}", id, e.getMessage());
                }
            }
            json(ctx, lines);
        });

        // Abre la carpeta de la instancia en el explorador de Windows.
        app.post("/instances/{id}/open", ctx -> {
            String id = ctx.pathParam("id");
            if (instanceManager.get(id) == null) { ctx.status(404).result("no existe"); return; }
            Path dir = LauncherPaths.instanceGameDir(id);
            try {
                new ProcessBuilder("explorer.exe", dir.toString()).start();
                ctx.status(204);
            } catch (Exception e) {
                log.warn("No se pudo abrir la carpeta de {}: {}", id, e.getMessage());
                ctx.status(500).result("No se pudo abrir la carpeta");
            }
        });

        // Sirve el icono PNG de la instancia (a <img>; token por query-param).
        app.get("/instances/{id}/icon", ctx -> {
            Path icon = LauncherPaths.instanceIcon(ctx.pathParam("id"));
            if (!Files.exists(icon)) { ctx.status(404); return; }
            ctx.contentType("image/png").header("Cache-Control", "no-cache");
            ctx.result(Files.readAllBytes(icon));
        });

        // Quita el icono personalizado (vuelve al placeholder).
        app.delete("/instances/{id}/icon", ctx -> {
            InstanceConfig cfg = instanceManager.get(ctx.pathParam("id"));
            if (cfg == null) { ctx.status(404).result("no existe"); return; }
            IconStore.delete(cfg.id);
            cfg.icon = "";
            instanceManager.save(cfg);
            ctx.status(204);
        });

        // Lista carpetas/archivos de la instancia (para el selector de exportación). path relativo opcional.
        app.get("/instances/{id}/files", ctx -> {
            InstanceConfig cfg = instanceManager.get(ctx.pathParam("id"));
            if (cfg == null) { ctx.status(404).result("no existe"); return; }
            Path base = LauncherPaths.instanceGameDir(cfg.id);
            String rel = ctx.queryParam("path");
            Path dir = (rel == null || rel.isBlank()) ? base : base.resolve(rel).normalize();
            if (!dir.startsWith(base) || !Files.isDirectory(dir)) { json(ctx, List.of()); return; }
            List<Map<String, Object>> out = new ArrayList<>();
            try (var s = Files.list(dir)) {
                var sorted = s.sorted(Comparator
                        .comparing((Path p) -> !Files.isDirectory(p))
                        .thenComparing(p -> p.getFileName().toString().toLowerCase())).toList();
                for (Path p : sorted) {
                    boolean d = Files.isDirectory(p);
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("name", p.getFileName().toString());
                    m.put("path", base.relativize(p).toString().replace('\\', '/'));
                    m.put("dir", d);
                    m.put("size", d ? 0L : Files.size(p));
                    out.add(m);
                }
            } catch (Exception e) {
                log.debug("listar archivos {}: {}", cfg.id, e.getMessage());
            }
            json(ctx, out);
        });

        // ── Ajustes globales ──
        app.get("/settings", ctx -> json(ctx, settingsView()));

        app.patch("/settings", ctx -> {
            Path oldInstances = LauncherPaths.instances();
            AppSettings.Patch p = gson.fromJson(ctx.body(), AppSettings.Patch.class);
            AppSettings.getInstance().apply(p);
            Path newInstances = LauncherPaths.instances();
            if (!oldInstances.equals(newInstances)) {
                instanceManager.migrateInstances(oldInstances, newInstances);
            }
            json(ctx, settingsView());
        });

        // ── Grupos de instancias (gestionados en la ventana de Instancias) ──
        // Devuelve la unión de los grupos persistidos (incl. vacíos) y los que ya
        // usan las instancias, ordenados alfabéticamente.
        app.get("/groups", ctx -> json(ctx, allGroups()));

        // Crea un grupo vacío.
        app.post("/groups", ctx -> {
            GroupReq req = gson.fromJson(ctx.body(), GroupReq.class);
            if (req == null || req.name == null || req.name.isBlank()) {
                ctx.status(400).result("name requerido"); return;
            }
            AppSettings.getInstance().addGroup(req.name);
            json(ctx, allGroups());
        });

        // Elimina un grupo: lo quita de la lista y desasigna las instancias que lo usaban.
        app.delete("/groups/{name}", ctx -> {
            String name = ctx.pathParam("name");
            AppSettings.getInstance().removeGroup(name);
            for (InstanceConfig cfg : instanceManager.list()) {
                if (cfg.group != null && cfg.group.equalsIgnoreCase(name)) {
                    cfg.group = "";
                    instanceManager.save(cfg);
                }
            }
            ctx.status(204);
        });

        // Purga la caché de la app (metadatos/HTTP). No toca datos de juego.
        app.post("/cache/purge", ctx -> {
            Map<String, Object> m = new HashMap<>();
            m.put("freedBytes", purgeAppCache());
            json(ctx, m);
        });

        // Abre la carpeta de logs en el explorador de Windows.
        app.post("/logs/open", ctx -> {
            try {
                new ProcessBuilder("explorer.exe", LogManager.logsDir().toString()).start();
                ctx.status(204);
            } catch (Exception e) {
                log.warn("No se pudo abrir la carpeta de logs: {}", e.getMessage());
                ctx.status(500).result("No se pudo abrir la carpeta de logs");
            }
        });

        // Genera un zip de diagnóstico (logs recientes + ajustes, sin tokens) en logs/.
        app.post("/logs/export", ctx -> {
            try {
                Path zip = exportDiagnostics();
                Map<String, Object> m = new HashMap<>();
                m.put("path", zip.toString());
                json(ctx, m);
            } catch (Exception e) {
                log.error("No se pudo exportar el diagnóstico: {}", e.getMessage(), e);
                ctx.status(500).result(e.getMessage());
            }
        });

        // ── Versiones ──
        app.get("/versions/vanilla", ctx -> {
            MojangMeta.Manifest m = vanilla.fetchManifest();
            List<Map<String, String>> out = new ArrayList<>();
            for (MojangMeta.Version v : m.versions) {
                Map<String, String> e = new LinkedHashMap<>();
                e.put("id", v.id);
                e.put("type", v.type);
                e.put("releaseTime", v.releaseTime);
                out.add(e);
            }
            json(ctx, out);
        });

        // Versiones de loader compatibles con una versión de MC.
        app.get("/loaders/{type}/versions", ctx -> {
            String type = ctx.pathParam("type");
            String mc = ctx.queryParam("mc");
            try {
                json(ctx, (mc == null || mc.isBlank()) ? List.of() : loaders.listVersions(type, mc));
            } catch (Exception e) {
                log.warn("No se pudieron listar versiones de {} para {}: {}", type, mc, e.getMessage());
                json(ctx, List.of());
            }
        });

        // ── Cuentas ──
        app.get("/accounts", ctx -> {
            List<Map<String, Object>> out = new ArrayList<>();
            for (StoredAccount a : accounts.list()) out.add(sanitize(a));
            json(ctx, out);
        });

        app.post("/accounts/offline", ctx -> {
            OfflineReq req = ctx.body().isBlank() ? new OfflineReq(null) : gson.fromJson(ctx.body(), OfflineReq.class);
            StoredAccount a = accounts.addOffline(req == null ? null : req.username);
            json(ctx, sanitize(a));
        });

        app.get("/config/microsoft", ctx -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("configured", accounts.microsoftConfigured());
            json(ctx, m);
        });

        // Inicia el login con Microsoft (device code): devuelve el código y la URL
        // que el usuario abre en el navegador; el resultado llega por WS ("auth").
        app.post("/accounts/microsoft/begin", ctx -> {
            try {
                AccountService.LoginPrompt p = accounts.beginMicrosoftLogin(new AccountService.LoginListener() {
                    @Override public void onSuccess(StoredAccount a) {
                        log.info("Cuenta Microsoft añadida: {}", a.username);
                        eventBus.publish("auth", authEvent("success", null, null, null, sanitize(a)));
                    }
                    @Override public void onError(String message) {
                        log.warn("Login Microsoft falló: {}", message);
                        eventBus.publish("auth", authEvent("error", null, null, message, null));
                    }
                });
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("userCode", p.userCode());
                m.put("verificationUri", p.verificationUri());
                m.put("expiresIn", p.expiresIn());
                json(ctx, m);
            } catch (AuthException e) {
                ctx.status(400).result(e.getMessage());
            }
        });

        app.post("/accounts/{id}/activate", ctx -> {
            accounts.setActive(ctx.pathParam("id"));
            ctx.status(204);
        });

        app.delete("/accounts/{id}", ctx -> {
            boolean ok = accounts.remove(ctx.pathParam("id"));
            ctx.status(ok ? 204 : 404);
        });

        // ── Skins y capas (cuenta Microsoft) ──
        app.get("/accounts/{id}/profile", ctx -> {
            try { json(ctx, accounts.profile(ctx.pathParam("id"))); }
            catch (AuthException e) { ctx.status(400).result(e.getMessage()); }
        });

        app.post("/accounts/{id}/skin", ctx -> {
            SkinReq r = gson.fromJson(ctx.body(), SkinReq.class);
            try { json(ctx, accounts.changeSkinUrl(ctx.pathParam("id"), r.url, r.variant)); }
            catch (AuthException e) { ctx.status(400).result(e.getMessage()); }
        });

        app.post("/accounts/{id}/skin/upload", ctx -> {
            try {
                SkinUploadReq r = gson.fromJson(ctx.body(), SkinUploadReq.class);
                byte[] png = java.util.Base64.getDecoder().decode(r.data);
                json(ctx, accounts.uploadSkin(ctx.pathParam("id"), png, r.fileName, r.variant));
            } catch (AuthException e) {
                ctx.status(400).result(e.getMessage());
            } catch (IllegalArgumentException e) {
                ctx.status(400).result("Archivo de skin no válido.");
            }
        });

        app.delete("/accounts/{id}/skin", ctx -> {
            try { json(ctx, accounts.resetSkin(ctx.pathParam("id"))); }
            catch (AuthException e) { ctx.status(400).result(e.getMessage()); }
        });

        app.put("/accounts/{id}/cape", ctx -> {
            CapeReq r = ctx.body().isBlank() ? new CapeReq(null) : gson.fromJson(ctx.body(), CapeReq.class);
            try { json(ctx, accounts.cape(ctx.pathParam("id"), r == null ? null : r.capeId)); }
            catch (AuthException e) { ctx.status(400).result(e.getMessage()); }
        });

        app.get("/accounts/{id}/skins", ctx -> {
            try { json(ctx, accounts.skins(ctx.pathParam("id"))); }
            catch (AuthException e) { ctx.status(400).result(e.getMessage()); }
        });

        // ── Contenido (mods, resourcepacks, shaders, datapacks) ──
        app.get("/content/search", ctx -> {
            try {
                var r = content.search(
                        qp(ctx, "source", "modrinth"), ContentType.from(qp(ctx, "type", "mods")),
                        ctx.queryParam("mc"), ctx.queryParam("loader"), ctx.queryParam("q"),
                        ctx.queryParam("category"), ctx.queryParam("env"), ctx.queryParam("sort"),
                        qpInt(ctx, "page", 0), qpBool(ctx, "ignore"));
                json(ctx, r);
            } catch (Exception e) {
                log.warn("Búsqueda de contenido falló: {}", e.getMessage());
                ctx.status(502).result(e.getMessage());
            }
        });

        app.get("/content/project", ctx -> {
            try {
                json(ctx, content.project(qp(ctx, "source", "modrinth"),
                        ContentType.from(qp(ctx, "type", "mods")), ctx.queryParam("id")));
            } catch (Exception e) {
                ctx.status(502).result(e.getMessage());
            }
        });

        app.get("/content/versions", ctx -> {
            try {
                json(ctx, content.versions(qp(ctx, "source", "modrinth"),
                        ContentType.from(qp(ctx, "type", "mods")), ctx.queryParam("id"),
                        ctx.queryParam("mc"), ctx.queryParam("loader"), qpBool(ctx, "ignore")));
            } catch (Exception e) {
                ctx.status(502).result(e.getMessage());
            }
        });

        app.get("/content/categories", ctx -> {
            try {
                json(ctx, content.categories(qp(ctx, "source", "modrinth"),
                        ContentType.from(qp(ctx, "type", "mods"))));
            } catch (Exception e) {
                json(ctx, Map.of());
            }
        });

        app.get("/instances/{id}/content", ctx -> {
            InstanceConfig cfg = instanceManager.get(ctx.pathParam("id"));
            if (cfg == null) { ctx.status(404).result("no existe"); return; }
            json(ctx, contentInstaller.list(cfg));
        });

        app.get("/instances/{id}/content/updates", ctx -> {
            InstanceConfig cfg = instanceManager.get(ctx.pathParam("id"));
            if (cfg == null) { ctx.status(404).result("no existe"); return; }
            try {
                json(ctx, contentInstaller.updates(cfg));
            } catch (Exception e) {
                ctx.status(502).result(e.getMessage());
            }
        });

        // Identifica mods añadidos manualmente (hash Modrinth / nombre CurseForge).
        app.post("/instances/{id}/content/identify", ctx -> {
            InstanceConfig cfg = instanceManager.get(ctx.pathParam("id"));
            if (cfg == null) { ctx.status(404).result("no existe"); return; }
            try {
                json(ctx, contentInstaller.identify(cfg));
            } catch (Exception e) {
                ctx.status(502).result(e.getMessage());
            }
        });

        // Informe de compatibilidad del contenido frente a una versión/loader objetivo (preview).
        app.get("/instances/{id}/content/compat", ctx -> {
            InstanceConfig cfg = instanceManager.get(ctx.pathParam("id"));
            if (cfg == null) { ctx.status(404).result("no existe"); return; }
            String mc = qp(ctx, "mc", cfg.mcVersion);
            String loader = qp(ctx, "loader", cfg.loader);
            try {
                json(ctx, contentInstaller.compat(cfg, mc, loader));
            } catch (Exception e) {
                ctx.status(502).result(e.getMessage());
            }
        });

        // Cambia la versión de MC / loader de la instancia y la reinstala (async, progreso por WS).
        app.post("/instances/{id}/change-version", ctx -> {
            InstanceConfig cfg = instanceManager.get(ctx.pathParam("id"));
            if (cfg == null) { ctx.status(404).result("no existe"); return; }
            if (blockIfModpack(cfg, ctx)) return;
            if (gameLauncher.isRunning(cfg.id)) {
                ctx.status(409).result("detén la instancia antes de cambiar la versión");
                return;
            }
            ChangeVersionReq req = gson.fromJson(ctx.body(), ChangeVersionReq.class);
            if (req == null || req.mcVersion == null || req.mcVersion.isBlank()) {
                ctx.status(400).result("mcVersion requerido");
                return;
            }
            String loader = (req.loader == null || req.loader.isBlank()) ? "vanilla" : req.loader.trim();
            String lv = req.loaderVersion == null ? "" : req.loaderVersion.trim();
            if (!"vanilla".equals(loader) && lv.isBlank()) {
                ctx.status(400).result("loaderVersion requerido");
                return;
            }
            cfg.mcVersion = req.mcVersion.trim();
            cfg.loader = loader;
            cfg.loaderVersion = "vanilla".equals(loader) ? "" : lv;
            cfg.installed = false;
            instanceManager.save(cfg);
            submitInstall(cfg.id, () -> runInstall(cfg));
            json(ctx, cfg);
        });

        // Verifica y repara el contenido (hashes) — re-descarga lo que falte o esté corrupto.
        app.post("/instances/{id}/repair", ctx -> {
            InstanceConfig cfg = instanceManager.get(ctx.pathParam("id"));
            if (cfg == null) { ctx.status(404).result("no existe"); return; }
            AppExecutors.io().submit(() -> {
                try {
                    eventBus.publish("state", stateEvent(cfg.id, "installing", null));
                    contentInstaller.repair(cfg, (phase, d, t) ->
                            eventBus.publish("progress", progressEvent(cfg.id, phase, d, t)));
                    eventBus.publish("state", stateEvent(cfg.id, "installed", null));
                } catch (Throwable e) {
                    log.error("Error reparando {}: {}", cfg.id, msg(e), e);
                    eventBus.publish("state", stateEvent(cfg.id, "error", msg(e)));
                }
            });
            ctx.status(202);
        });

        app.post("/instances/{id}/content/install", ctx -> {
            InstanceConfig cfg = instanceManager.get(ctx.pathParam("id"));
            if (cfg == null) { ctx.status(404).result("no existe"); return; }
            if (blockIfModpack(cfg, ctx)) return; // añadir/actualizar mods deshabilitado en modpacks
            InstallReq req = gson.fromJson(ctx.body(), InstallReq.class);
            try {
                InstalledItem item = contentInstaller.install(cfg, req.source,
                        ContentType.from(req.type), req.projectId, req.versionId, req.ignore,
                        (phase, d, t) -> eventBus.publish("contentProgress", progressEvent(cfg.id, phase, d, t)));
                json(ctx, item);
            } catch (Exception e) {
                log.error("Error instalando contenido en {}: {}", cfg.id, e.getMessage());
                ctx.status(502).result(e.getMessage());
            }
        });

        app.post("/instances/{id}/content/toggle", ctx -> {
            InstanceConfig cfg = instanceManager.get(ctx.pathParam("id"));
            if (cfg == null) { ctx.status(404).result("no existe"); return; }
            FileReq req = gson.fromJson(ctx.body(), FileReq.class);
            try {
                boolean ok = contentInstaller.toggle(cfg, req.type, req.fileName);
                ctx.status(ok ? 204 : 404);
            } catch (Exception e) {
                ctx.status(502).result(e.getMessage());
            }
        });

        app.post("/instances/{id}/content/remove", ctx -> {
            InstanceConfig cfg = instanceManager.get(ctx.pathParam("id"));
            if (cfg == null) { ctx.status(404).result("no existe"); return; }
            if (blockIfModpack(cfg, ctx)) return; // quitar mods deshabilitado en modpacks
            FileReq req = gson.fromJson(ctx.body(), FileReq.class);
            try {
                boolean ok = contentInstaller.remove(cfg, req.type, req.fileName);
                ctx.status(ok ? 204 : 404);
            } catch (Exception e) {
                ctx.status(502).result(e.getMessage());
            }
        });

        // Importa archivos sueltos (.jar/.zip) arrastrados a la instancia → carpeta correcta + identify.
        app.post("/instances/{id}/content/import-file", ctx -> {
            InstanceConfig cfg = instanceManager.get(ctx.pathParam("id"));
            if (cfg == null) { ctx.status(404).result("no existe"); return; }
            if (blockIfModpack(cfg, ctx)) return; // añadir mods (arrastrar archivos) deshabilitado en modpacks
            ImportFilesReq req = gson.fromJson(ctx.body(), ImportFilesReq.class);
            if (req == null || req.filePaths == null || req.filePaths.isEmpty()) {
                ctx.status(400).result("filePaths requerido");
                return;
            }
            try {
                json(ctx, contentInstaller.importFiles(cfg, req.filePaths, req.type));
            } catch (Exception e) {
                ctx.status(502).result(e.getMessage());
            }
        });

        // ── Modpacks privados (installer_config.json remoto) ──
        app.get("/modpacks", ctx -> {
            try {
                json(ctx, modpacks.list());
            } catch (Exception e) {
                log.warn("No se pudo cargar el catálogo de modpacks: {}", e.getMessage());
                ctx.status(502).result(e.getMessage());
            }
        });

        // Descarga (proxy) el markdown de descripción de un modpack desde su URL remota.
        app.get("/modpacks/readme", ctx -> {
            String url = ctx.queryParam("url");
            if (url == null || url.isBlank()) { ctx.status(400).result("url requerida"); return; }
            try {
                String md = content.fetchText(ModpackService.cacheBust(ModpackService.toRawUrl(url)));
                ctx.contentType("text/markdown; charset=utf-8").result(md.getBytes(StandardCharsets.UTF_8));
            } catch (Exception e) {
                ctx.status(502).result(e.getMessage());
            }
        });

        // Crea una instancia nueva a partir de un modpack y la instala (async, progreso por WS).
        // Responde de inmediato: la descarga del pack, el parse y la instalación corren en
        // segundo plano emitiendo progreso por WS desde el primer instante (la barra ya no
        // se congela en "Preparando…" mientras se descarga/parsea un modpack pesado).
        app.post("/modpacks/install", ctx -> {
            ModpackInstallReq req = gson.fromJson(ctx.body(), ModpackInstallReq.class);
            if (req == null || req.downloadUrl == null || req.downloadUrl.isBlank()) {
                ctx.status(400).result("downloadUrl requerido");
                return;
            }
            try {
                String name = (req.name != null && !req.name.isBlank()) ? req.name : "Modpack";
                // La versión de MC puede venir vacía: se deduce del pack al parsearlo (async).
                InstanceConfig cfg = instanceManager.createForModpack(
                        name, nz(req.mcVersion), nz(req.loader), nz(req.loaderVersion));
                cfg.sourceModpackId = nz(req.id);
                cfg.modpackVersion = nz(req.version);
                cfg.modpackVariant = "lite".equalsIgnoreCase(req.variant) ? "lite" : "standard";
                instanceManager.save(cfg);

                final String url = req.downloadUrl;
                final String iconUrl = nz(req.icon);
                submitInstall(cfg.id, () -> runModpackInstallFromUrl(cfg, url, iconUrl));
                json(ctx, cfg);
            } catch (Exception e) {
                log.error("Error preparando modpack: {}", e.getMessage(), e);
                ctx.status(502).result(e.getMessage());
            }
        });

        // Importa un modpack desde un archivo local (.f24pack/.mrpack/.zip) → instancia nueva (async).
        app.post("/modpacks/import", ctx -> {
            ImportReq req = gson.fromJson(ctx.body(), ImportReq.class);
            if (req == null || req.filePath == null || req.filePath.isBlank()) {
                ctx.status(400).result("filePath requerido");
                return;
            }
            Path source = Paths.get(req.filePath);
            if (!Files.isRegularFile(source)) { ctx.status(400).result("archivo no encontrado"); return; }
            Path packFile = null;
            try {
                // Copia a un temporal: runModpackInstall borra el pack al terminar y no
                // queremos borrar el archivo original del usuario.
                String ext = req.filePath.toLowerCase(Locale.ROOT).endsWith(".mrpack") ? ".mrpack" : ".zip";
                packFile = Files.createTempFile("f24-import-", ext);
                Files.copy(source, packFile, StandardCopyOption.REPLACE_EXISTING);
                ModpackInstaller.Parsed parsed = modpackInstaller.parse(packFile);
                String mc = nz(parsed.mcVersion());
                if (mc.isBlank()) {
                    Files.deleteIfExists(packFile);
                    ctx.status(400).result("No se pudo determinar la versión de Minecraft del modpack");
                    return;
                }
                String name = (parsed.name() != null && !parsed.name().isBlank())
                        ? parsed.name() : fileBaseName(source);
                InstanceConfig cfg = instanceManager.create(name, mc, parsed.loader(), parsed.loaderVersion());
                applyF24Meta(cfg, packFile);
                instanceManager.save(cfg);
                final Path pf = packFile;
                submitInstall(cfg.id, () -> runModpackInstall(cfg, pf, parsed));
                json(ctx, cfg);
            } catch (Exception e) {
                log.error("Error importando modpack: {}", e.getMessage(), e);
                try { if (packFile != null) Files.deleteIfExists(packFile); } catch (Exception ignored) {}
                ctx.status(502).result(e.getMessage());
            }
        });

        // Exporta una instancia a .f24pack / .mrpack en la ruta elegida por el usuario.
        app.post("/instances/{id}/export", ctx -> {
            InstanceConfig cfg = instanceManager.get(ctx.pathParam("id"));
            if (cfg == null) { ctx.status(404).result("no existe"); return; }
            ModpackExporter.Options opt = gson.fromJson(ctx.body(), ModpackExporter.Options.class);
            if (opt == null || opt.outputPath == null || opt.outputPath.isBlank()) {
                ctx.status(400).result("outputPath requerido");
                return;
            }
            try {
                new ModpackExporter().export(cfg, opt);
                ctx.status(204);
            } catch (Exception e) {
                log.error("Error exportando {}: {}", cfg.id, e.getMessage(), e);
                ctx.status(502).result(e.getMessage());
            }
        });

        // Estado de actualización de una instancia de modpack (versión instalada vs catálogo).
        app.get("/instances/{id}/modpack", ctx -> {
            InstanceConfig cfg = instanceManager.get(ctx.pathParam("id"));
            if (cfg == null) { ctx.status(404).result("no existe"); return; }
            if (!cfg.isModpack()) { json(ctx, Map.of("isModpack", false)); return; }
            String latest = "";
            try {
                Modpack mp = findModpack(cfg.sourceModpackId);
                if (mp != null) latest = nz(mp.version());
            } catch (Exception e) {
                log.debug("No se pudo consultar el catálogo para {}: {}", cfg.id, e.getMessage());
            }
            json(ctx, Map.of(
                    "isModpack", true,
                    "modpackId", nz(cfg.sourceModpackId),
                    "variant", nz(cfg.modpackVariant),
                    "current", nz(cfg.modpackVersion),
                    "latest", latest,
                    "updateAvailable", ModpackUpdater.isNewer(latest, cfg.modpackVersion)));
        });

        // Actualiza una instancia de modpack a la última versión del catálogo (diferencial, async).
        app.post("/instances/{id}/modpack/update", ctx -> {
            InstanceConfig cfg = instanceManager.get(ctx.pathParam("id"));
            if (cfg == null) { ctx.status(404).result("no existe"); return; }
            if (!cfg.isModpack()) { ctx.status(400).result("no es una instancia de modpack"); return; }
            if (gameLauncher.isRunning(cfg.id)) {
                ctx.status(409).result("detén la instancia antes de actualizar");
                return;
            }
            Modpack mp;
            try {
                mp = findModpack(cfg.sourceModpackId);
            } catch (Exception e) {
                ctx.status(502).result("no se pudo cargar el catálogo: " + e.getMessage());
                return;
            }
            if (mp == null) { ctx.status(404).result("el modpack ya no está en el catálogo"); return; }
            if (!ModpackUpdater.isNewer(nz(mp.version()), cfg.modpackVersion)) {
                ctx.status(409).result("la instancia ya está en la última versión");
                return;
            }
            final Modpack target = mp;
            final String url = mp.urlFor(cfg.modpackVariant);
            submitInstall(cfg.id, () -> runModpackUpdate(cfg, target, url));
            json(ctx, cfg);
        });

        // ── Eventos ──
        app.ws("/events", ws -> {
            ws.onConnect(eventBus::register);
            ws.onClose(eventBus::unregister);
        });
    }

    /** Busca un modpack del catálogo remoto por su id; null si no está. */
    private Modpack findModpack(String modpackId) throws Exception {
        if (modpackId == null || modpackId.isBlank()) return null;
        for (Modpack mp : modpacks.list()) if (modpackId.equals(mp.id())) return mp;
        return null;
    }

    /** Descarga la versión y emite progreso/estado por WebSocket. */
    /** Lanza una instalación cancelable: registra su Future y limpia el estado al terminar. */
    private void submitInstall(String id, Runnable task) {
        Future<?> f = AppExecutors.io().submit(() -> {
            try { task.run(); }
            finally { installs.remove(id); cancelled.remove(id); }
        });
        installs.put(id, f);
    }

    /** ¿La instalación de esta instancia fue cancelada por el usuario? */
    private boolean wasCancelled(String id) { return cancelled.contains(id); }

    private void runInstall(InstanceConfig cfg) {
        long t0 = System.nanoTime();
        try {
            log.info("Instalando instancia {} (MC {} · loader {}). Memoria: {}",
                    cfg.id, cfg.mcVersion, cfg.loader, LogManager.memoryInfo());
            eventBus.publish("state", stateEvent(cfg.id, "installing", null));
            loaders.install(cfg, (phase, done, total) ->
                    eventBus.publish("progress", progressEvent(cfg.id, phase, done, total)));

            // Resolvemos (y descargamos si falta) el runtime Java requerido AHORA,
            // durante la instalación, para no bloquear el primer "Jugar".
            VersionDetails v = vanilla.resolveVersion(cfg.mcVersion);
            int major = (v.javaVersion != null) ? v.javaVersion.majorVersion : 21;
            eventBus.publish("progress", progressEvent(cfg.id, "Java " + major, 0, 1));
            runtimes.resolveJavaExe(cfg, major);
            eventBus.publish("progress", progressEvent(cfg.id, "Java " + major, 1, 1));

            cfg.installed = true;
            instanceManager.save(cfg);
            eventBus.publish("state", stateEvent(cfg.id, "installed", null));
            log.info("Instancia {} instalada (MC {}) en {} ms. Memoria: {}",
                    cfg.id, cfg.mcVersion, (System.nanoTime() - t0) / 1_000_000, LogManager.memoryInfo());
        } catch (Throwable e) {
            // Throwable (no solo Exception): un Error como OutOfMemoryError debe
            // reportarse y loguearse, no morir en silencio dejando la UI colgada.
            if (wasCancelled(cfg.id)) {
                log.info("Instalación de {} cancelada.", cfg.id);
                eventBus.publish("state", stateEvent(cfg.id, "stopped", "Instalación cancelada"));
            } else {
                log.error("Error instalando {}: {}", cfg.id, msg(e), e);
                eventBus.publish("state", stateEvent(cfg.id, "error", msg(e)));
            }
        }
    }

    /**
     * Descarga el archivo del modpack (con progreso por WS), deduce versión/loader del
     * propio pack y delega en {@link #runModpackInstall}. Corre en la tarea async de
     * instalación, de modo que el POST de /modpacks/install responde al instante.
     */
    private void runModpackInstallFromUrl(InstanceConfig cfg, String url, String iconUrl) {
        Path packFile = null;
        try {
            eventBus.publish("state", stateEvent(cfg.id, "installing", null));
            VanillaInstaller.Sink sink = (phase, done, total) ->
                    eventBus.publish("progress", progressEvent(cfg.id, phase, done, total));

            // El icono del catálogo pasa a ser el icono de la instancia (cuanto antes).
            applyModpackIcon(cfg, iconUrl);

            packFile = modpackInstaller.download(url, sink);
            sink.update("Preparando modpack", 0, 1);
            ModpackInstaller.Parsed parsed = modpackInstaller.parse(packFile);
            sink.update("Preparando modpack", 1, 1);

            String mc = !parsed.mcVersion().isBlank() ? parsed.mcVersion() : nz(cfg.mcVersion);
            if (mc.isBlank())
                throw new IllegalStateException("No se pudo determinar la versión de Minecraft del modpack");
            cfg.mcVersion = mc;
            if (!parsed.loader().isBlank()) {
                cfg.loader = parsed.loader();
                cfg.loaderVersion = nz(parsed.loaderVersion());
            }
            instanceManager.save(cfg);

            final Path pf = packFile;
            packFile = null; // la propiedad pasa a runModpackInstall (lo borra en su finally)
            runModpackInstall(cfg, pf, parsed);
        } catch (Throwable e) {
            if (wasCancelled(cfg.id)) {
                log.info("Instalación de modpack en {} cancelada.", cfg.id);
                eventBus.publish("state", stateEvent(cfg.id, "stopped", "Instalación cancelada"));
            } else {
                log.error("Error instalando modpack en {}: {}", cfg.id, msg(e), e);
                eventBus.publish("state", stateEvent(cfg.id, "error", msg(e)));
            }
            if (packFile != null) {
                try { Files.deleteIfExists(packFile); } catch (Exception ignored) {}
            }
        }
    }

    /**
     * Actualiza una instancia de modpack a una versión nueva del catálogo de forma
     * diferencial: descarga el nuevo pack, calcula el diff contra el manifiesto instalado
     * y solo aplica lo que cambió (mods nuevos/cambiados/retirados y las configs que el
     * modpack modificó), sin tocar saves/options/logs ni archivos del usuario.
     */
    private void runModpackUpdate(InstanceConfig cfg, Modpack mp, String url) {
        Path packFile = null;
        try {
            eventBus.publish("state", stateEvent(cfg.id, "installing", null));
            VanillaInstaller.Sink sink = (phase, done, total) ->
                    eventBus.publish("progress", progressEvent(cfg.id, phase, done, total));

            packFile = modpackInstaller.download(url, sink);
            sink.update("Analizando actualización", 0, 1);
            ModpackInstaller.Parsed parsed = modpackInstaller.parse(packFile);
            ModpackInstaller.PackContents next = modpackInstaller.scan(packFile, parsed);
            sink.update("Analizando actualización", 1, 1);

            // La versión de MC / loader puede cambiar entre versiones del modpack: si cambia,
            // se reinstala la base (loaders.install es idempotente y baja lo que falte).
            boolean baseChanged = false;
            if (!parsed.mcVersion().isBlank() && !parsed.mcVersion().equals(cfg.mcVersion)) {
                cfg.mcVersion = parsed.mcVersion();
                baseChanged = true;
            }
            if (!parsed.loader().isBlank()
                    && (!parsed.loader().equals(cfg.loader) || !nz(parsed.loaderVersion()).equals(nz(cfg.loaderVersion)))) {
                cfg.loader = parsed.loader();
                cfg.loaderVersion = nz(parsed.loaderVersion());
                baseChanged = true;
            }
            if (baseChanged) {
                instanceManager.save(cfg);
                loaders.install(cfg, sink);
            }

            ModpackManifest old = ModpackManifest.load(cfg.id);
            ModpackInstaller.Plan plan = ModpackUpdater.diff(old, next);
            log.info("Actualización de modpack {}: {} descarga(s) · {} config(s) · {} retirado(s)",
                    cfg.id, plan.downloads().size(), plan.extracts().size(), plan.removals().size());
            modpackInstaller.applyPlan(packFile, LauncherPaths.instanceGameDir(cfg.id), plan, sink);

            // Nuevo manifiesto + versión instalada.
            modpackInstaller.manifestFrom(next, cfg.sourceModpackId, mp.version(),
                    cfg.modpackVariant, parsed.format()).save(cfg.id);
            cfg.modpackVersion = nz(mp.version());

            VersionDetails v = vanilla.resolveVersion(cfg.mcVersion);
            int major = (v.javaVersion != null) ? v.javaVersion.majorVersion : 21;
            runtimes.resolveJavaExe(cfg, major);

            cfg.installed = true;
            instanceManager.save(cfg);
            eventBus.publish("state", stateEvent(cfg.id, "installed", null));
            log.info("Modpack {} actualizado a {} en instancia {}.", cfg.sourceModpackId, mp.version(), cfg.id);
        } catch (Throwable e) {
            if (wasCancelled(cfg.id)) {
                log.info("Actualización de modpack en {} cancelada.", cfg.id);
                eventBus.publish("state", stateEvent(cfg.id, "stopped", "Actualización cancelada"));
            } else {
                log.error("Error actualizando modpack en {}: {}", cfg.id, msg(e), e);
                eventBus.publish("state", stateEvent(cfg.id, "error", msg(e)));
            }
        } finally {
            if (packFile != null) {
                try { Files.deleteIfExists(packFile); } catch (Exception ignored) {}
            }
        }
    }

    /** Instala una instancia creada desde un modpack: vanilla+loader, contenido, overrides, Java. */
    private void runModpackInstall(InstanceConfig cfg, Path packFile, ModpackInstaller.Parsed parsed) {
        long t0 = System.nanoTime();
        try {
            log.info("Instalando modpack en {} (MC {} · loader {} · {} archivos). Memoria: {}",
                    cfg.id, cfg.mcVersion, cfg.loader, parsed.files().size(), LogManager.memoryInfo());
            eventBus.publish("state", stateEvent(cfg.id, "installing", null));
            VanillaInstaller.Sink sink = (phase, done, total) ->
                    eventBus.publish("progress", progressEvent(cfg.id, phase, done, total));

            loaders.install(cfg, sink);
            modpackInstaller.apply(packFile, parsed, LauncherPaths.instanceGameDir(cfg.id), sink);

            // Manifiesto del modpack instalado (rutas + hashes): base de la actualización diferencial.
            try {
                ModpackInstaller.PackContents pc = modpackInstaller.scan(packFile, parsed);
                modpackInstaller.manifestFrom(pc, cfg.sourceModpackId, cfg.modpackVersion,
                        cfg.modpackVariant, parsed.format()).save(cfg.id);
            } catch (Exception e) {
                log.warn("No se pudo guardar el manifiesto del modpack {}: {}", cfg.id, e.getMessage());
            }

            VersionDetails v = vanilla.resolveVersion(cfg.mcVersion);
            int major = (v.javaVersion != null) ? v.javaVersion.majorVersion : 21;
            eventBus.publish("progress", progressEvent(cfg.id, "Java " + major, 0, 1));
            runtimes.resolveJavaExe(cfg, major);
            eventBus.publish("progress", progressEvent(cfg.id, "Java " + major, 1, 1));

            cfg.installed = true;
            instanceManager.save(cfg);
            eventBus.publish("state", stateEvent(cfg.id, "installed", null));
            log.info("Modpack instalado en instancia {} (MC {} · {}) en {} ms. Memoria: {}",
                    cfg.id, cfg.mcVersion, cfg.loader, (System.nanoTime() - t0) / 1_000_000, LogManager.memoryInfo());
        } catch (Throwable e) {
            // Throwable (no solo Exception): un Error como OutOfMemoryError debe
            // reportarse y loguearse, no morir en silencio dejando la UI colgada.
            if (wasCancelled(cfg.id)) {
                log.info("Instalación de modpack en {} cancelada.", cfg.id);
                eventBus.publish("state", stateEvent(cfg.id, "stopped", "Instalación cancelada"));
            } else {
                log.error("Error instalando modpack en {}: {}", cfg.id, msg(e), e);
                eventBus.publish("state", stateEvent(cfg.id, "error", msg(e)));
            }
        } finally {
            try { Files.deleteIfExists(packFile); } catch (Exception ignored) {}
        }
    }

    /** Lanza el juego y emite logs/estado por WebSocket. */
    private void runLaunch(InstanceConfig cfg, Account account) {
        try {
            eventBus.publish("state", stateEvent(cfg.id, "launching", null));
            long startMs = System.currentTimeMillis();
            gameLauncher.launch(cfg, account, new GameLauncher.LogSink() {
                @Override public void onLine(String line) {
                    eventBus.publish("log", logEvent(cfg.id, line));
                }
                @Override public void onExit(int code) {
                    long elapsed = Math.max(0, System.currentTimeMillis() - startMs);
                    // Recarga la config (pudo editarse durante la sesión) y acumula el
                    // tiempo de juego total + la fecha de la última sesión (P5).
                    InstanceConfig fresh = instanceManager.get(cfg.id);
                    if (fresh != null) {
                        fresh.totalPlayMs += elapsed;
                        fresh.lastPlayed = System.currentTimeMillis();
                        instanceManager.save(fresh);
                    }
                    // Detección de crash (P8): salida no nula o crash-report nuevo.
                    String crash = recentCrashReport(cfg.id, startMs);
                    if (code != 0 || crash != null)
                        eventBus.publish("crash", crashEvent(cfg.id, code, crash));
                    eventBus.publish("state", stateEvent(cfg.id, "stopped", "exit " + code));
                }
            });
            cfg.lastPlayed = System.currentTimeMillis();
            instanceManager.save(cfg);
            eventBus.publish("state", stateEvent(cfg.id, "running", null));
        } catch (Throwable e) {
            log.error("Error lanzando {}: {}", cfg.id, msg(e), e);
            eventBus.publish("state", stateEvent(cfg.id, "error", msg(e)));
        }
    }

    /** Crash-report más reciente generado desde {@code sinceMs}, como ruta relativa, o null. */
    private String recentCrashReport(String id, long sinceMs) {
        Path dir = LauncherPaths.instanceGameDir(id).resolve("crash-reports");
        if (!Files.isDirectory(dir)) return null;
        try (Stream<Path> files = Files.list(dir)) {
            Path newest = files.filter(Files::isRegularFile)
                    .filter(p -> { String n = p.getFileName().toString().toLowerCase();
                                   return n.endsWith(".txt") || n.endsWith(".log"); })
                    .max(Comparator.comparingLong(IpcServer::mtime))
                    .orElse(null);
            if (newest == null || mtime(newest) < sinceMs) return null;
            return "crash-reports/" + newest.getFileName();
        } catch (Exception e) {
            return null;
        }
    }

    private static long mtime(Path p) {
        try { return Files.getLastModifiedTime(p).toMillis(); } catch (Exception e) { return 0; }
    }

    private Map<String, Object> crashEvent(String id, int code, String file) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("instanceId", id);
        m.put("code", code);
        m.put("file", file == null ? "" : file);
        return m;
    }

    private Map<String, Object> logEvent(String id, String line) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("instanceId", id);
        m.put("line", line);
        return m;
    }

    private Map<String, Object> progressEvent(String id, String phase, long done, long total) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("instanceId", id);
        m.put("phase", phase);
        m.put("done", done);
        m.put("total", total);
        return m;
    }

    /**
     * Empaqueta un zip de diagnóstico (logs sueltos de la sesión actual + ajustes) en
     * la carpeta de logs. No incluye accounts.json (contiene tokens). Devuelve la ruta.
     */
    private Path exportDiagnostics() throws java.io.IOException {
        Path logs = LogManager.logsDir();
        String ts = java.time.LocalDateTime.now()
                .format(java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"));
        Path zip = logs.resolve("diagnostico-" + ts + ".zip");
        Path[] candidates = {
                logs.resolve(LogManager.BACKEND_LOG),
                logs.resolve(LogManager.FRONTEND_LOG),
                LauncherPaths.root().resolve("launcher.json")
        };
        try (java.util.zip.ZipOutputStream zos =
                     new java.util.zip.ZipOutputStream(Files.newOutputStream(zip))) {
            for (Path f : candidates) {
                if (Files.isRegularFile(f)) {
                    zos.putNextEntry(new java.util.zip.ZipEntry(f.getFileName().toString()));
                    Files.copy(f, zos);
                    zos.closeEntry();
                }
            }
        }
        log.info("Diagnóstico exportado a {}", zip);
        return zip;
    }

    /** Purga la caché de la app: caché de disco HTTP + metadatos en memoria. Devuelve bytes liberados. */
    private long purgeAppCache() {
        HttpConnectionPool http = HttpConnectionPool.getInstance();
        long freed = http.cacheSize();
        http.evictCache();
        http.evictConnections();
        content.clearCaches();
        return freed;
    }

    /** Unión ordenada de los grupos persistidos (incl. vacíos) y los usados por las instancias. */
    private List<String> allGroups() {
        TreeSet<String> set = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
        set.addAll(AppSettings.getInstance().getGroups());
        for (InstanceConfig cfg : instanceManager.list()) {
            if (cfg.group != null && !cfg.group.isBlank()) set.add(cfg.group.trim());
        }
        return new ArrayList<>(set);
    }

    /** Vista serializable de los ajustes globales. */
    private Map<String, Object> settingsView() {
        AppSettings s = AppSettings.getInstance();
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("darkMode", s.isDarkMode());
        m.put("showBetaVersions", s.isShowBetaVersions());
        m.put("instancesPath", s.getInstancesPath());
        m.put("defaultInstancesPath", LauncherPaths.root().resolve("instances").toString());
        m.put("defaultMinMemoryMb", s.getDefaultMinMemoryMb());
        m.put("defaultMaxMemoryMb", s.getDefaultMaxMemoryMb());
        m.put("defaultWindowWidth", s.getDefaultWindowWidth());
        m.put("defaultWindowHeight", s.getDefaultWindowHeight());
        m.put("defaultJvmArgs", s.getDefaultJvmArgs());
        m.put("launcherWidth", s.getLauncherWidth());
        m.put("launcherHeight", s.getLauncherHeight());
        m.put("closeToBackground", s.isCloseToBackground());
        m.put("minimizeOnLaunch", s.isMinimizeOnLaunch());
        m.put("maxConcurrentDownloads", s.getMaxConcurrentDownloads());
        m.put("maxConcurrentWrites", s.getMaxConcurrentWrites());
        return m;
    }

    /** Vista pública de una cuenta (sin tokens). */
    private Map<String, Object> sanitize(StoredAccount a) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", a.id);
        m.put("type", a.type);
        m.put("username", a.username);
        m.put("uuid", a.uuid);
        m.put("skinUrl", a.skinUrl);
        m.put("active", a.active);
        m.put("expired", a.isMicrosoft() && a.tokenExpired());
        return m;
    }

    private Map<String, Object> authEvent(String stage, String userCode, String verificationUri,
                                          String message, Map<String, Object> account) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("stage", stage);
        if (userCode != null) m.put("userCode", userCode);
        if (verificationUri != null) m.put("verificationUri", verificationUri);
        if (message != null) m.put("message", message);
        if (account != null) m.put("account", account);
        return m;
    }

    private Map<String, Object> stateEvent(String id, String state, String message) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("instanceId", id);
        m.put("state", state);
        if (message != null) m.put("message", message);
        return m;
    }

    /**
     * Admin restringida (0.0.5): rechaza con 403 las acciones no permitidas en una
     * instancia gestionada por un modpack (cambiar versión/loader, añadir/quitar/actualizar
     * mods). Habilitar/deshabilitar y el resto de ajustes siguen permitidos. Devuelve true
     * si bloqueó (el handler debe salir).
     */
    private boolean blockIfModpack(InstanceConfig cfg, io.javalin.http.Context ctx) {
        if (cfg.isModpack()) {
            ctx.status(403).result("Esta instancia está gestionada por un modpack; esa acción no está permitida.");
            return true;
        }
        return false;
    }

    private void json(io.javalin.http.Context ctx, Object body) {
        ctx.contentType("application/json; charset=utf-8")
                .result(gson.toJson(body).getBytes(StandardCharsets.UTF_8));
    }

    private static String qp(io.javalin.http.Context ctx, String key, String def) {
        String v = ctx.queryParam(key);
        return (v == null || v.isBlank()) ? def : v;
    }

    private static int qpInt(io.javalin.http.Context ctx, String key, int def) {
        try { return Integer.parseInt(ctx.queryParam(key)); } catch (Exception e) { return def; }
    }

    private static boolean qpBool(io.javalin.http.Context ctx, String key) {
        return "true".equalsIgnoreCase(ctx.queryParam(key));
    }

    private static String nz(String s) { return s == null ? "" : s; }

    /** Mensaje legible de un Throwable (un Error como OutOfMemoryError suele traer message null). */
    private static String msg(Throwable t) {
        String m = t.getMessage();
        return (m != null && !m.isBlank()) ? m : t.getClass().getSimpleName();
    }

    /**
     * Aplica el icono del modpack (URL del catálogo o data-URL) como icono de la instancia,
     * salvo que ya tenga uno. El icono se normaliza a PNG 256x256 en {@link IconStore}.
     */
    private void applyModpackIcon(InstanceConfig cfg, String iconUrl) {
        if (iconUrl == null || iconUrl.isBlank() || IconStore.exists(cfg.id)) return;
        try {
            boolean ok = iconUrl.startsWith("data:")
                    ? IconStore.save(cfg.id, iconUrl)
                    : IconStore.saveBytes(cfg.id, HttpConnectionPool.getInstance().getBytes(iconUrl));
            if (ok) {
                cfg.icon = "icon.png";
                instanceManager.save(cfg);
            }
        } catch (Exception e) {
            log.debug("No se pudo aplicar el icono del modpack a {}: {}", cfg.id, e.getMessage());
        }
    }

    /** Aplica los extras de un .f24pack (icono, memoria, jvm, ventana) a la instancia creada. */
    private void applyF24Meta(InstanceConfig cfg, Path packFile) {
        try {
            ModpackInstaller.F24Meta meta = modpackInstaller.readF24Meta(packFile);
            String iconName = "icon.png";
            if (meta != null) {
                if (meta.minMemoryMb() > 0) cfg.minMemoryMb = clamp(meta.minMemoryMb(), 256, 65536);
                if (meta.maxMemoryMb() > 0) cfg.maxMemoryMb = clamp(meta.maxMemoryMb(), 512, 65536);
                if (cfg.maxMemoryMb < cfg.minMemoryMb) cfg.maxMemoryMb = cfg.minMemoryMb;
                if (meta.windowWidth() > 0) cfg.windowWidth = Math.max(640, meta.windowWidth());
                if (meta.windowHeight() > 0) cfg.windowHeight = Math.max(480, meta.windowHeight());
                if (meta.jvmArgs() != null && !meta.jvmArgs().isBlank()) cfg.jvmArgs = meta.jvmArgs().trim();
                if (meta.sourceModpackId() != null && !meta.sourceModpackId().isBlank())
                    cfg.sourceModpackId = meta.sourceModpackId();
                if (meta.icon() != null && !meta.icon().isBlank()) iconName = meta.icon();
            }
            byte[] icon = modpackInstaller.readEntry(packFile, iconName);
            if (icon != null && IconStore.saveBytes(cfg.id, icon)) cfg.icon = "icon.png";
        } catch (Exception e) {
            log.debug("f24 meta import: {}", e.getMessage());
        }
    }

    private static String fileBaseName(Path p) {
        String n = p.getFileName().toString();
        int dot = n.lastIndexOf('.');
        return dot > 0 ? n.substring(0, dot) : n;
    }

    private static int clamp(int v, int min, int max) { return Math.max(min, Math.min(max, v)); }

    public String getToken() { return token; }

    public EventBus getEventBus() { return eventBus; }

    public void stop() {
        if (app != null) {
            try { app.stop(); } catch (Exception e) { log.warn("Error deteniendo IPC: {}", e.getMessage()); }
        }
    }

    private record CreateReq(String name, String mcVersion, String loader, String loaderVersion,
                             Integer minMemoryMb, Integer maxMemoryMb, Integer windowWidth,
                             Integer windowHeight, Boolean fullscreen, String jvmArgs,
                             String javaPathOverride, String iconData) {}
    private record LaunchReq(String username) {}
    private record OfflineReq(String username) {}
    private record InstallReq(String source, String type, String projectId, String versionId, boolean ignore) {}
    private record ChangeVersionReq(String mcVersion, String loader, String loaderVersion) {}
    private record FileReq(String type, String fileName) {}
    private record ImportFilesReq(List<String> filePaths, String type) {}
    private record ModpackInstallReq(String id, String name, String downloadUrl,
                                     String mcVersion, String loader, String loaderVersion,
                                     String version, String variant, String icon) {}
    private record ImportReq(String filePath) {}
    private record UpdateReq(String name, Integer minMemoryMb, Integer maxMemoryMb,
                             Integer windowWidth, Integer windowHeight, Boolean fullscreen,
                             String jvmArgs, String javaPathOverride, String iconData,
                             Boolean favorite, String group) {}
    private record GroupReq(String name) {}
    private record SkinReq(String url, String variant) {}
    private record SkinUploadReq(String data, String fileName, String variant) {}
    private record CapeReq(String capeId) {}
}
