package f24launcher.core.launch;

import f24launcher.auth.Account;
import f24launcher.core.LauncherPaths;
import f24launcher.core.meta.MojangMeta.*;
import f24launcher.core.version.LibraryRules;
import f24launcher.instance.InstanceConfig;

import com.google.gson.JsonElement;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Construye el comando completo de lanzamiento: JVM args + classpath + mainClass
 * + game args, sustituyendo los placeholders ${...} de la versión.
 */
public class ArgumentBuilder {

    public List<String> build(InstanceConfig cfg, VersionDetails v, Account account, String javaExe) {
        String nativesDir = LauncherPaths.versionNatives(cfg.versionKey()).toString();
        String gameDir = LauncherPaths.instanceGameDir(cfg.id).toString();
        String classpath = buildClasspath(cfg.mcVersion, v);

        Map<String, String> vars = placeholders(cfg, v, account, gameDir, classpath);
        boolean modular = isForge(cfg.loader) && v.arguments != null && v.arguments.jvm != null;

        List<String> cmd = new ArrayList<>();
        cmd.add(javaExe);
        cmd.add("-Xms" + Math.max(256, cfg.minMemoryMb) + "m");
        cmd.add("-Xmx" + Math.max(cfg.minMemoryMb, cfg.maxMemoryMb) + "m");
        if (cfg.jvmArgs != null && !cfg.jvmArgs.isBlank()) {
            for (String a : cfg.jvmArgs.trim().split("\\s+")) cmd.add(a);
        }
        if (modular) {
            cmd.add("-Djava.library.path=" + nativesDir);
            cmd.add("-Dorg.lwjgl.librarypath=" + nativesDir);
            cmd.add("-Djna.tmpdir=" + nativesDir);
            cmd.addAll(jvmArgs(v, vars));
        } else {
            cmd.add("-Djava.library.path=" + nativesDir);
            cmd.add("-Djna.tmpdir=" + nativesDir);
            cmd.add("-Dminecraft.launcher.brand=f24launcher");
            cmd.add("-Dminecraft.launcher.version=1.0.0");
            cmd.add("-cp");
            cmd.add(classpath);
        }
        cmd.add(v.mainClass);

        cmd.addAll(gameArgs(v, vars));

        if (cfg.fullscreen) {
            cmd.add("--fullscreen");
        } else {
            cmd.add("--width");
            cmd.add(String.valueOf(cfg.windowWidth));
            cmd.add("--height");
            cmd.add(String.valueOf(cfg.windowHeight));
        }
        return cmd;
    }

    private String buildClasspath(String mcVersion, VersionDetails v) {
        List<String> cp = new ArrayList<>();
        java.util.Set<String> seen = new java.util.HashSet<>();
        for (Library lib : v.libraries) {
            if (!LibraryRules.usableOnWindows(lib)) continue;
            if (LibraryRules.isWindowsNative(lib)) continue; // los natives no van al classpath
            if (lib.downloads != null && lib.downloads.artifact != null && lib.downloads.artifact.path != null) {
                String key = artifactKey(lib.name);
                if (key != null && !seen.add(key)) continue; // dedup grupo:artefacto (gana el primero = loader)
                cp.add(LauncherPaths.library(lib.downloads.artifact.path).toString());
            }
        }
        cp.add(LauncherPaths.versionJar(mcVersion).toString());
        return String.join(";", cp);
    }

    private String artifactKey(String name) {
        if (name == null) return null;
        String[] p = name.split(":");
        if (p.length < 2) return name;
        String classifier = p.length >= 4 ? p[3] : "";
        return p[0] + ":" + p[1] + ":" + classifier;
    }

    private boolean isForge(String loader) {
        return "forge".equals(loader) || "neoforge".equals(loader);
    }

    private List<String> jvmArgs(VersionDetails v, Map<String, String> vars) {
        List<String> out = new ArrayList<>();
        for (JsonElement el : v.arguments.jvm) {
            if (el.isJsonPrimitive()) {
                out.add(substitute(el.getAsString(), vars));
            } else if (el.isJsonObject()) {
                var obj = el.getAsJsonObject();
                if (!ruleAllowsWindows(obj)) continue;
                var val = obj.get("value");
                if (val == null) continue;
                if (val.isJsonPrimitive()) {
                    out.add(substitute(val.getAsString(), vars));
                } else if (val.isJsonArray()) {
                    val.getAsJsonArray().forEach(e -> out.add(substitute(e.getAsString(), vars)));
                }
            }
        }
        return out;
    }

    private boolean ruleAllowsWindows(com.google.gson.JsonObject argObj) {
        if (!argObj.has("rules")) return true;
        boolean allowed = false;
        for (JsonElement re : argObj.getAsJsonArray("rules")) {
            var rule = re.getAsJsonObject();
            String action = rule.has("action") ? rule.get("action").getAsString() : "allow";
            boolean matches = true;
            if (rule.has("os")) {
                var os = rule.getAsJsonObject("os");
                if (os.has("name")) matches = "windows".equals(os.get("name").getAsString());
            }
            if (matches) allowed = "allow".equals(action);
        }
        return allowed;
    }

    private List<String> gameArgs(VersionDetails v, Map<String, String> vars) {
        List<String> raw = new ArrayList<>();
        if (v.arguments != null && v.arguments.game != null) {
            // Formato nuevo (>=1.13): solo los argumentos planos (los condicionales
            // por features —demo, quickPlay, resolución— se omiten).
            for (JsonElement el : v.arguments.game) {
                if (el.isJsonPrimitive()) raw.add(el.getAsString());
            }
        } else if (v.minecraftArguments != null) {
            // Formato antiguo (<1.13).
            for (String s : v.minecraftArguments.split("\\s+")) raw.add(s);
        }
        List<String> out = new ArrayList<>();
        for (String arg : raw) out.add(substitute(arg, vars));
        return out;
    }

    private Map<String, String> placeholders(InstanceConfig cfg, VersionDetails v, Account acc,
                                             String gameDir, String classpath) {
        Map<String, String> m = new HashMap<>();
        m.put("auth_player_name", acc.username());
        m.put("version_name", cfg.mcVersion);
        m.put("game_directory", gameDir);
        m.put("assets_root", LauncherPaths.assets().toString());
        m.put("game_assets", LauncherPaths.assets().toString());
        m.put("assets_index_name", v.assetIndex != null ? v.assetIndex.id : (v.assets != null ? v.assets : "legacy"));
        m.put("auth_uuid", acc.uuid());
        m.put("auth_access_token", acc.accessToken());
        m.put("auth_session", acc.accessToken());
        m.put("clientid", "");
        m.put("auth_xuid", acc.xuid() != null ? acc.xuid() : "");
        m.put("user_type", acc.userType());
        m.put("version_type", v.type != null ? v.type : "release");
        m.put("user_properties", "{}");
        m.put("natives_directory", LauncherPaths.versionNatives(cfg.versionKey()).toString());
        m.put("classpath", classpath);
        m.put("classpath_separator", ";");
        m.put("library_directory", LauncherPaths.libraries().toString());
        m.put("launcher_name", "f24launcher");
        m.put("launcher_version", "1.0.0");
        return m;
    }

    private String substitute(String arg, Map<String, String> vars) {
        String out = arg;
        for (Map.Entry<String, String> e : vars.entrySet()) {
            out = out.replace("${" + e.getKey() + "}", e.getValue());
        }
        return out;
    }
}
