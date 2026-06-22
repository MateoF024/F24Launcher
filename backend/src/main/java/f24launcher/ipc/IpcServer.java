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
import f24launcher.instance.InstanceConfig;
import f24launcher.instance.InstanceManager;
import f24launcher.modpack.ModpackInstaller;
import f24launcher.modpack.ModpackService;
import f24launcher.settings.AppSettings;
import f24launcher.util.AppExecutors;

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
import java.util.*;

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

    private Javalin app;

    public int start() {
        app = Javalin.create(cfg -> {
            cfg.showJavalinBanner = false;
            cfg.bundledPlugins.enableCors(cors -> cors.addRule(rule -> rule.anyHost()));
        });

        app.before(ctx -> {
            if (ctx.method() == HandlerType.OPTIONS || "/health".equals(ctx.path())) return;
            // El WebSocket no puede enviar cabeceras custom → token por query-param.
            if ("/events".equals(ctx.path())) {
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
        app.after(ctx -> log.info("← {} {}", ctx.path(), ctx.status()));

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
            AppExecutors.io().submit(() -> runInstall(cfg));
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

        // Devuelve las últimas líneas del latest.log de la instancia (para la consola).
        app.get("/instances/{id}/log", ctx -> {
            String id = ctx.pathParam("id");
            if (instanceManager.get(id) == null) { ctx.status(404).result("no existe"); return; }
            Path logFile = LauncherPaths.instanceGameDir(id).resolve("logs").resolve("latest.log");
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

        app.post("/instances/{id}/content/install", ctx -> {
            InstanceConfig cfg = instanceManager.get(ctx.pathParam("id"));
            if (cfg == null) { ctx.status(404).result("no existe"); return; }
            InstallReq req = gson.fromJson(ctx.body(), InstallReq.class);
            try {
                InstalledItem item = contentInstaller.install(cfg, req.source,
                        ContentType.from(req.type), req.projectId, req.versionId, req.ignore);
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
            FileReq req = gson.fromJson(ctx.body(), FileReq.class);
            try {
                boolean ok = contentInstaller.remove(cfg, req.type, req.fileName);
                ctx.status(ok ? 204 : 404);
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
        app.post("/modpacks/install", ctx -> {
            ModpackInstallReq req = gson.fromJson(ctx.body(), ModpackInstallReq.class);
            if (req == null || req.downloadUrl == null || req.downloadUrl.isBlank()) {
                ctx.status(400).result("downloadUrl requerido");
                return;
            }
            Path packFile = null;
            try {
                packFile = modpackInstaller.download(req.downloadUrl, (p, d, t) -> {});
                ModpackInstaller.Parsed parsed = modpackInstaller.parse(packFile);
                String mc = !parsed.mcVersion().isBlank() ? parsed.mcVersion() : nz(req.mcVersion);
                if (mc.isBlank()) {
                    Files.deleteIfExists(packFile);
                    ctx.status(400).result("No se pudo determinar la versión de Minecraft del modpack");
                    return;
                }
                String loader = !parsed.loader().isBlank() ? parsed.loader() : nz(req.loader);
                String lv = !parsed.loaderVersion().isBlank() ? parsed.loaderVersion() : nz(req.loaderVersion);
                String name = (parsed.name() != null && !parsed.name().isBlank()) ? parsed.name() : nz(req.name);

                InstanceConfig cfg = instanceManager.create(name, mc, loader, lv);
                cfg.sourceModpackId = nz(req.id);
                instanceManager.save(cfg);

                final Path pf = packFile;
                AppExecutors.io().submit(() -> runModpackInstall(cfg, pf, parsed));
                json(ctx, cfg);
            } catch (Exception e) {
                log.error("Error preparando modpack: {}", e.getMessage(), e);
                try { if (packFile != null) Files.deleteIfExists(packFile); } catch (Exception ignored) {}
                ctx.status(502).result(e.getMessage());
            }
        });

        // ── Eventos ──
        app.ws("/events", ws -> {
            ws.onConnect(eventBus::register);
            ws.onClose(eventBus::unregister);
        });
    }

    /** Descarga la versión y emite progreso/estado por WebSocket. */
    private void runInstall(InstanceConfig cfg) {
        try {
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
            log.info("Instancia {} instalada (MC {}).", cfg.id, cfg.mcVersion);
        } catch (Exception e) {
            log.error("Error instalando {}: {}", cfg.id, e.getMessage(), e);
            eventBus.publish("state", stateEvent(cfg.id, "error", e.getMessage()));
        }
    }

    /** Instala una instancia creada desde un modpack: vanilla+loader, contenido, overrides, Java. */
    private void runModpackInstall(InstanceConfig cfg, Path packFile, ModpackInstaller.Parsed parsed) {
        try {
            eventBus.publish("state", stateEvent(cfg.id, "installing", null));
            VanillaInstaller.Sink sink = (phase, done, total) ->
                    eventBus.publish("progress", progressEvent(cfg.id, phase, done, total));

            loaders.install(cfg, sink);
            modpackInstaller.apply(packFile, parsed, LauncherPaths.instanceGameDir(cfg.id), sink);

            VersionDetails v = vanilla.resolveVersion(cfg.mcVersion);
            int major = (v.javaVersion != null) ? v.javaVersion.majorVersion : 21;
            eventBus.publish("progress", progressEvent(cfg.id, "Java " + major, 0, 1));
            runtimes.resolveJavaExe(cfg, major);
            eventBus.publish("progress", progressEvent(cfg.id, "Java " + major, 1, 1));

            cfg.installed = true;
            instanceManager.save(cfg);
            eventBus.publish("state", stateEvent(cfg.id, "installed", null));
            log.info("Modpack instalado en instancia {} (MC {} · {}).", cfg.id, cfg.mcVersion, cfg.loader);
        } catch (Exception e) {
            log.error("Error instalando modpack en {}: {}", cfg.id, e.getMessage(), e);
            eventBus.publish("state", stateEvent(cfg.id, "error", e.getMessage()));
        } finally {
            try { Files.deleteIfExists(packFile); } catch (Exception ignored) {}
        }
    }

    /** Lanza el juego y emite logs/estado por WebSocket. */
    private void runLaunch(InstanceConfig cfg, Account account) {
        try {
            eventBus.publish("state", stateEvent(cfg.id, "launching", null));
            gameLauncher.launch(cfg, account, new GameLauncher.LogSink() {
                @Override public void onLine(String line) {
                    eventBus.publish("log", logEvent(cfg.id, line));
                }
                @Override public void onExit(int code) {
                    eventBus.publish("state", stateEvent(cfg.id, "stopped", "exit " + code));
                }
            });
            cfg.lastPlayed = System.currentTimeMillis();
            instanceManager.save(cfg);
            eventBus.publish("state", stateEvent(cfg.id, "running", null));
        } catch (Exception e) {
            log.error("Error lanzando {}: {}", cfg.id, e.getMessage(), e);
            eventBus.publish("state", stateEvent(cfg.id, "error", e.getMessage()));
        }
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
                             String javaPathOverride) {}
    private record LaunchReq(String username) {}
    private record OfflineReq(String username) {}
    private record InstallReq(String source, String type, String projectId, String versionId, boolean ignore) {}
    private record FileReq(String type, String fileName) {}
    private record ModpackInstallReq(String id, String name, String downloadUrl,
                                     String mcVersion, String loader, String loaderVersion) {}
    private record UpdateReq(String name, Integer minMemoryMb, Integer maxMemoryMb,
                             Integer windowWidth, Integer windowHeight, Boolean fullscreen,
                             String jvmArgs, String javaPathOverride) {}
    private record SkinReq(String url, String variant) {}
    private record SkinUploadReq(String data, String fileName, String variant) {}
    private record CapeReq(String capeId) {}
}
