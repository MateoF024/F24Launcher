package f24launcher.util;

import f24launcher.core.LauncherPaths;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Gestión de los archivos de log del launcher (Fase L1 de la 0.0.3).
 *
 * Igual que Minecraft: en cada arranque, los logs "sueltos" de la sesión anterior
 * ({@code backend-latest.log} y, cuando exista, {@code frontend-latest.log}) se
 * comprimen a {@code logs/archived/<timestamp>.zip} y la sesión nueva empieza con un
 * {@code backend-latest.log} limpio. El histórico se poda a {@link #KEEP_ARCHIVES}.
 *
 * <p><b>Importante:</b> {@link #setup()} debe llamarse <i>antes</i> de crear cualquier
 * {@code Logger}, porque slf4j-simple lee las propiedades del sistema al inicializarse.
 * Por eso esta clase no tiene un logger propio y solo usa {@code System.err} para sus
 * propios fallos (que serían anteriores a que el logging esté operativo).
 */
public final class LogManager {

    public static final String BACKEND_LOG = "backend-latest.log";
    public static final String FRONTEND_LOG = "frontend-latest.log";

    /** Cuántos .zip de sesiones anteriores se conservan en logs/archived/. */
    private static final int KEEP_ARCHIVES = 20;

    private LogManager() {}

    /** Carpeta de logs de la app: {@code %APPDATA%/F24Launcher/logs}. */
    public static Path logsDir() {
        Path p = LauncherPaths.root().resolve("logs");
        try { Files.createDirectories(p); } catch (Exception ignored) {}
        return p;
    }

    /**
     * Rota los logs de la sesión anterior y deja slf4j-simple escribiendo en
     * {@code logs/backend-latest.log} con un formato legible.
     */
    public static void setup() {
        Path logs = logsDir();
        try {
            archivePrevious(logs);
        } catch (Exception e) {
            System.err.println("No se pudieron rotar los logs anteriores: " + e.getMessage());
        }
        configureSimpleLogger(logs.resolve(BACKEND_LOG));
    }

    /** Comprime los *-latest.log existentes a un único .zip y los borra. */
    private static void archivePrevious(Path logs) throws IOException {
        List<Path> toArchive = new ArrayList<>();
        for (String name : new String[]{BACKEND_LOG, FRONTEND_LOG}) {
            Path f = logs.resolve(name);
            if (Files.isRegularFile(f) && Files.size(f) > 0) toArchive.add(f);
        }
        if (toArchive.isEmpty()) return;

        Path archived = logs.resolve("archived");
        Files.createDirectories(archived);

        // El nombre del zip refleja la fecha del log más reciente de la sesión previa.
        long stamp = 0;
        for (Path f : toArchive) stamp = Math.max(stamp, Files.getLastModifiedTime(f).toMillis());
        if (stamp == 0) stamp = System.currentTimeMillis();
        String ts = LocalDateTime.ofInstant(Instant.ofEpochMilli(stamp), ZoneId.systemDefault())
                .format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"));

        Path zip = uniqueZip(archived, ts);
        try (ZipOutputStream zos = new ZipOutputStream(Files.newOutputStream(zip))) {
            for (Path f : toArchive) {
                zos.putNextEntry(new ZipEntry(f.getFileName().toString()));
                Files.copy(f, zos);
                zos.closeEntry();
            }
        }
        for (Path f : toArchive) {
            try { Files.deleteIfExists(f); } catch (Exception ignored) {}
        }
        prune(archived);
    }

    private static Path uniqueZip(Path dir, String ts) {
        Path zip = dir.resolve(ts + ".zip");
        int n = 1;
        while (Files.exists(zip)) zip = dir.resolve(ts + "-" + (n++) + ".zip");
        return zip;
    }

    /** Conserva solo los {@link #KEEP_ARCHIVES} zips más recientes. */
    private static void prune(Path archived) {
        try (var s = Files.list(archived)) {
            List<Path> zips = s.filter(p -> p.getFileName().toString().endsWith(".zip"))
                    .sorted(Comparator.comparingLong(LogManager::lastModified))
                    .toList();
            int excess = zips.size() - KEEP_ARCHIVES;
            for (int i = 0; i < excess; i++) {
                try { Files.deleteIfExists(zips.get(i)); } catch (Exception ignored) {}
            }
        } catch (Exception ignored) {}
    }

    private static long lastModified(Path p) {
        try { return Files.getLastModifiedTime(p).toMillis(); } catch (Exception e) { return 0; }
    }

    /** Configura slf4j-simple (sin pisar overrides externos por -D). */
    private static void configureSimpleLogger(Path file) {
        setIfAbsent("org.slf4j.simpleLogger.logFile", file.toString());
        setIfAbsent("org.slf4j.simpleLogger.showDateTime", "true");
        setIfAbsent("org.slf4j.simpleLogger.dateTimeFormat", "yyyy-MM-dd'T'HH:mm:ss.SSS");
        setIfAbsent("org.slf4j.simpleLogger.showThreadName", "true");
        setIfAbsent("org.slf4j.simpleLogger.showShortLogName", "true");
        setIfAbsent("org.slf4j.simpleLogger.levelInBrackets", "true");
        setIfAbsent("org.slf4j.simpleLogger.defaultLogLevel", "info");
    }

    private static void setIfAbsent(String key, String value) {
        if (System.getProperty(key) == null) System.setProperty(key, value);
    }

    // ── Diagnóstico (L2) ──────────────────────────────────────────────

    /** Resumen compacto del uso de memoria del heap del backend (para el log). */
    public static String memoryInfo() {
        Runtime rt = Runtime.getRuntime();
        long mb = 1024L * 1024L;
        long max = rt.maxMemory();
        long total = rt.totalMemory();
        long used = total - rt.freeMemory();
        double pct = max > 0 ? used * 100.0 / max : 0;
        return String.format("usado %d MB · heap %d MB · máx %d MB (%.0f%%)",
                used / mb, total / mb, max / mb, pct);
    }
}
