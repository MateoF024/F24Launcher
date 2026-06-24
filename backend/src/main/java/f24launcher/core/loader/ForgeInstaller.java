package f24launcher.core.loader;

import f24launcher.core.LauncherPaths;
import f24launcher.core.meta.MojangMeta.*;
import f24launcher.core.runtime.JavaRuntimeManager;
import f24launcher.core.version.MavenCoord;
import f24launcher.core.version.VanillaInstaller;
import f24launcher.instance.InstanceConfig;
import f24launcher.util.DownloadManager;
import f24launcher.util.HttpConnectionPool;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/** Instala Forge y NeoForge: descarga el installer, baja librerías y ejecuta los processors. */
public class ForgeInstaller {

    private static final Logger log = LoggerFactory.getLogger(ForgeInstaller.class);
    private final Gson gson = new Gson();
    private final HttpConnectionPool http = HttpConnectionPool.getInstance();
    private final DownloadManager dm = DownloadManager.getInstance();
    private final VanillaInstaller vanilla = new VanillaInstaller();
    private final JavaRuntimeManager runtimes = new JavaRuntimeManager();

    public boolean supports(String type) {
        return "forge".equals(type) || "neoforge".equals(type);
    }

    public List<String> listVersions(String type, String mc) throws Exception {
        if ("forge".equals(type)) {
            String xml = http.get("https://maven.minecraftforge.net/net/minecraftforge/forge/maven-metadata.xml");
            List<String> out = new ArrayList<>();
            for (String v : versionsFromMetadata(xml)) {
                if (v.startsWith(mc + "-")) out.add(v.substring((mc + "-").length()));
            }
            java.util.Collections.reverse(out);
            return out;
        }
        if ("neoforge".equals(type)) {
            String xml = http.get("https://maven.neoforged.net/releases/net/neoforged/neoforge/maven-metadata.xml");
            String prefix = neoforgePrefix(mc);
            List<String> out = new ArrayList<>();
            for (String v : versionsFromMetadata(xml)) {
                if (prefix == null || v.startsWith(prefix)) out.add(v);
            }
            java.util.Collections.reverse(out);
            return out;
        }
        return List.of();
    }

    private String neoforgePrefix(String mc) {
        String v = mc.startsWith("1.") ? mc.substring(2) : mc;
        String[] p = v.split("\\.");
        if (p.length >= 2) return p[0] + "." + p[1] + ".";
        if (p.length == 1 && !p[0].isEmpty()) return p[0] + ".0.";
        return null;
    }

    private List<String> versionsFromMetadata(String xml) {
        List<String> out = new ArrayList<>();
        Matcher m = Pattern.compile("<version>([^<]+)</version>").matcher(xml);
        while (m.find()) out.add(m.group(1));
        return out;
    }

    private String installerUrl(String type, String mc, String lver) {
        if ("forge".equals(type)) {
            String full = mc + "-" + lver;
            return "https://maven.minecraftforge.net/net/minecraftforge/forge/" + full
                    + "/forge-" + full + "-installer.jar";
        }
        return "https://maven.neoforged.net/releases/net/neoforged/neoforge/" + lver
                + "/neoforge-" + lver + "-installer.jar";
    }

    /** Descarga el installer, extrae version.json al caché de loaders, baja libs y corre processors. */
    public void install(InstanceConfig cfg, VanillaInstaller.Sink sink, Path profileCache) throws Exception {
        VersionDetails base = vanilla.resolveVersion(cfg.mcVersion);
        int major = base.javaVersion != null ? base.javaVersion.majorVersion : 21;
        String javaExe = runtimes.resolveJavaExe(cfg, major);

        sink.update(cfg.loader, 0, 1);
        Path installersDir = LauncherPaths.root().resolve("loaders").resolve("installers");
        Files.createDirectories(installersDir);
        Path installer = installersDir.resolve(cfg.loader + "-" + cfg.mcVersion + "-" + cfg.loaderVersion + "-installer.jar");
        if (!Files.exists(installer) || Files.size(installer) == 0) {
            DownloadManager.Result r = dm.download(new DownloadManager.Task(
                    installerUrl(cfg.loader, cfg.mcVersion, cfg.loaderVersion), installer, 0, null, null), false);
            if (r.status() == DownloadManager.Status.FAILED)
                throw new Exception("No se pudo descargar el installer de " + cfg.loader, r.error());
        }

        try (ZipFile zip = new ZipFile(installer.toFile())) {
            String versionJson = readEntry(zip, "version.json");
            String installProfile = readEntry(zip, "install_profile.json");
            if (versionJson == null || installProfile == null) {
                throw new Exception("El installer no contiene version.json / install_profile.json");
            }
            Files.writeString(profileCache, versionJson);

            JsonObject profile = gson.fromJson(installProfile, JsonObject.class);
            Path libDir = LauncherPaths.libraries();

            sink.update(cfg.loader, 0, 1);
            downloadLibraries(profile, zip, libDir);
            VersionDetails vj = gson.fromJson(versionJson, VersionDetails.class);
            downloadVersionLibraries(vj, zip, libDir);

            runProcessors(cfg, profile, zip, libDir, installer, javaExe, sink);
        }
        log.info("{} {} instalado para MC {}.", cfg.loader, cfg.loaderVersion, cfg.mcVersion);
    }

    private void downloadLibraries(JsonObject profile, ZipFile zip, Path libDir) throws Exception {
        if (!profile.has("libraries")) return;
        var arr = profile.getAsJsonArray("libraries");
        for (int i = 0; i < arr.size(); i++) {
            fetchLibrary(arr.get(i).getAsJsonObject(), zip, libDir);
        }
    }

    private void downloadVersionLibraries(VersionDetails vj, ZipFile zip, Path libDir) throws Exception {
        if (vj.libraries == null) return;
        for (Library lib : vj.libraries) {
            if (lib.downloads != null && lib.downloads.artifact != null) {
                writeArtifact(lib.downloads.artifact.path, lib.downloads.artifact.url, zip, libDir);
            } else if (lib.name != null) {
                writeArtifact(MavenCoord.path(lib.name), null, zip, libDir);
            }
        }
    }

    private void fetchLibrary(JsonObject lib, ZipFile zip, Path libDir) throws Exception {
        if (lib.has("downloads")) {
            JsonObject dl = lib.getAsJsonObject("downloads");
            if (dl.has("artifact")) {
                JsonObject a = dl.getAsJsonObject("artifact");
                writeArtifact(str(a, "path"), str(a, "url"), zip, libDir);
                return;
            }
        }
        if (lib.has("name")) writeArtifact(MavenCoord.path(str(lib, "name")), null, zip, libDir);
    }

    private void writeArtifact(String path, String url, ZipFile zip, Path libDir) throws Exception {
        if (path == null) return;
        Path dest = libDir.resolve(path);
        if (Files.exists(dest) && Files.size(dest) > 0) return;
        if (url != null && !url.isBlank()) {
            byte[] data = http.getBytes(url);
            Files.createDirectories(dest.getParent());
            Files.write(dest, data);
            return;
        }
        ZipEntry e = zip.getEntry("maven/" + path);
        if (e != null) {
            Files.createDirectories(dest.getParent());
            try (InputStream in = zip.getInputStream(e)) { Files.copy(in, dest); }
        }
    }

    private void runProcessors(InstanceConfig cfg, JsonObject profile, ZipFile zip, Path libDir,
                               Path installer, String javaExe, VanillaInstaller.Sink sink) throws Exception {
        if (!profile.has("processors")) return;
        Map<String, String> data = resolveData(cfg, profile, zip, libDir);
        data.put("SIDE", "client");
        data.put("MINECRAFT_JAR", LauncherPaths.versionJar(cfg.mcVersion).toString());
        data.put("ROOT", LauncherPaths.root().toString());
        data.put("INSTALLER", installer.toString());
        data.put("LIBRARY_DIR", libDir.toString());

        var procs = profile.getAsJsonArray("processors");
        int total = procs.size();
        for (int i = 0; i < total; i++) {
            JsonObject proc = procs.get(i).getAsJsonObject();
            if (!appliesToClient(proc)) continue;
            sink.update(cfg.loader + " (processors)", i, total);

            java.util.LinkedHashSet<String> cp = new java.util.LinkedHashSet<>();
            cp.add(libDir.resolve(MavenCoord.path(str(proc, "jar"))).toString());
            if (proc.has("classpath")) {
                var pcp = proc.getAsJsonArray("classpath");
                for (int j = 0; j < pcp.size(); j++) {
                    cp.add(libDir.resolve(MavenCoord.path(pcp.get(j).getAsString())).toString());
                }
            }
            String mainClass = mainClassOf(libDir.resolve(MavenCoord.path(str(proc, "jar"))));
            if (mainClass == null) throw new Exception("Processor sin Main-Class: " + str(proc, "jar"));

            List<String> cmd = new ArrayList<>();
            cmd.add(javaExe);
            cmd.add("-cp");
            cmd.add(String.join(";", cp));
            cmd.add(mainClass);
            if (proc.has("args")) {
                var args = proc.getAsJsonArray("args");
                for (int j = 0; j < args.size(); j++) cmd.add(substitute(args.get(j).getAsString(), data, libDir));
            }
            runJava(cmd, str(proc, "jar"));
        }
        sink.update(cfg.loader + " (processors)", total, total);
    }

    private boolean appliesToClient(JsonObject proc) {
        if (!proc.has("sides")) return true;
        var sides = proc.getAsJsonArray("sides");
        for (int i = 0; i < sides.size(); i++) if ("client".equals(sides.get(i).getAsString())) return true;
        return false;
    }

    private Map<String, String> resolveData(InstanceConfig cfg, JsonObject profile, ZipFile zip, Path libDir) throws Exception {
        Map<String, String> out = new LinkedHashMap<>();
        if (!profile.has("data")) return out;
        JsonObject data = profile.getAsJsonObject("data");
        Path tmp = LauncherPaths.root().resolve("loaders").resolve("installers")
                .resolve("extracted").resolve(cfg.loader + "-" + cfg.mcVersion + "-" + cfg.loaderVersion);
        Files.createDirectories(tmp);
        for (String key : data.keySet()) {
            JsonObject entry = data.getAsJsonObject(key);
            if (!entry.has("client")) continue;
            String v = entry.get("client").getAsString();
            out.put(key, resolveValue(v, zip, libDir, tmp));
        }
        return out;
    }

    private String resolveValue(String v, ZipFile zip, Path libDir, Path tmp) throws Exception {
        if (v == null || v.isEmpty()) return v;
        if (v.startsWith("[") && v.endsWith("]")) {
            return libDir.resolve(MavenCoord.path(v.substring(1, v.length() - 1))).toString();
        }
        if (v.startsWith("'") && v.endsWith("'")) return v.substring(1, v.length() - 1);
        if (v.startsWith("/")) {
            ZipEntry e = zip.getEntry(v.substring(1));
            if (e == null) return v;
            Path dest = tmp.resolve(v.substring(1));
            Files.createDirectories(dest.getParent());
            try (InputStream in = zip.getInputStream(e)) {
                Files.copy(in, dest, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }
            return dest.toString();
        }
        return v;
    }

    private String substitute(String arg, Map<String, String> data, Path libDir) {
        if (arg == null) return "";
        if (arg.startsWith("[") && arg.endsWith("]")) {
            return libDir.resolve(MavenCoord.path(arg.substring(1, arg.length() - 1))).toString();
        }
        if (arg.startsWith("'") && arg.endsWith("'")) return arg.substring(1, arg.length() - 1);
        Matcher m = Pattern.compile("\\{([^}]+)}").matcher(arg);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            String val = data.getOrDefault(m.group(1), m.group(0));
            m.appendReplacement(sb, Matcher.quoteReplacement(val));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    private void runJava(List<String> cmd, String name) throws Exception {
        log.info("[FORGE] processor {} -> {}", name, cmd);
        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.redirectErrorStream(true);
        Process p = pb.start();
        StringBuilder sb = new StringBuilder();
        try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) sb.append(line).append('\n');
        }
        int code = p.waitFor();
        if (code != 0) {
            log.warn("[FORGE] processor {} salida:\n{}", name, sb);
            throw new Exception("El processor " + name + " falló (exit " + code + "). Salida: "
                    + sb.substring(Math.max(0, sb.length() - 600)));
        }
    }

    private String mainClassOf(Path jar) throws Exception {
        try (ZipFile zf = new ZipFile(jar.toFile())) {
            ZipEntry e = zf.getEntry("META-INF/MANIFEST.MF");
            if (e == null) return null;
            try (BufferedReader r = new BufferedReader(new InputStreamReader(zf.getInputStream(e), StandardCharsets.UTF_8))) {
                String line;
                while ((line = r.readLine()) != null) {
                    if (line.startsWith("Main-Class:")) return line.substring("Main-Class:".length()).trim();
                }
            }
        }
        return null;
    }

    private String readEntry(ZipFile zip, String name) throws Exception {
        ZipEntry e = zip.getEntry(name);
        if (e == null) return null;
        try (InputStream in = zip.getInputStream(e)) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static String str(JsonObject o, String k) {
        return (o != null && o.has(k) && !o.get(k).isJsonNull()) ? o.get(k).getAsString() : null;
    }
}
