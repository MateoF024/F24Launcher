package bundle.download;

import bundle.config.DownloadConfig;
import bundle.util.HttpConnectionPool;
import bundle.util.MemoryManager;

import okhttp3.Response;
import okhttp3.ResponseBody;

import java.io.*;
import java.nio.file.*;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

public final class DownloadManager {

    private static final int MAX_RETRIES   = 3;
    private static final int RETRY_DELAY_MS = 2000;

    private DownloadManager() { }

    public static List<DownloadException> downloadFilesTo(Path targetDir, DownloadConfig dlConfig, ProgressCallback progressCallback) {
        List<DownloadException> errors = new ArrayList<>();

        for (String url : dlConfig.urls) {
            boolean success = false;

            for (int attempt = 1; attempt <= MAX_RETRIES; attempt++) {
                try {
                    if (attempt > 1) {
                        System.out.println("[DOWNLOAD] Reintento " + attempt + "/" + MAX_RETRIES + " para: " + url);
                        Thread.sleep(RETRY_DELAY_MS * attempt);
                    }

                    if (progressCallback != null && attempt == 1) {
                        progressCallback.onPhaseStart("Descarga", 1);
                    }

                    downloadTo(targetDir, url, progressCallback);

                    if (progressCallback != null) {
                        progressCallback.onPhaseComplete("Descarga");
                    }

                    success = true;
                    break;

                } catch (DownloadException | IOException e) {
                    System.err.println("[DOWNLOAD ERROR] Intento " + attempt + "/" + MAX_RETRIES + ": " + e.getMessage());
                    if (attempt == MAX_RETRIES) {
                        errors.add(new DownloadException(
                                "Descarga fallida tras " + MAX_RETRIES + " intentos desde: " + url +
                                        "\nÚltimo error: " + e.getMessage(), e));
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    errors.add(new DownloadException("Descarga interrumpida: " + url, e));
                    break;
                }
            }

            if (!success) {
                System.err.println("[DOWNLOAD] Todos los intentos fallaron para: " + url);
            }
        }

        return errors;
    }

    private static void downloadTo(Path targetDir, String url, ProgressCallback progressCallback)
            throws IOException, DownloadException {

        if (!Files.exists(targetDir))    throw new DownloadException("El directorio destino no existe: " + targetDir);
        if (!Files.isWritable(targetDir)) throw new DownloadException("Sin permisos de escritura en: " + targetDir);

        // #18 — Buffer dinámico según memoria disponible
        int bufferSize = MemoryManager.getCurrentMemoryInfo().optimalBufferSize;

        try (Response response = HttpConnectionPool.getInstance().getRaw(url)) {
            if (!response.isSuccessful()) {
                throw new DownloadException("HTTP " + response.code() + " para: " + url);
            }

            ResponseBody body = response.body();
            if (body == null) throw new DownloadException("Respuesta vacía desde: " + url);

            long contentLength = body.contentLength();
            String fileName = extractFileName(response, url);
            Path tempFile = Files.createTempFile(targetDir, "dl-", ".part");

            try (InputStream input = body.byteStream();
                 OutputStream output = new BufferedOutputStream(
                         Files.newOutputStream(tempFile, StandardOpenOption.WRITE,
                                 StandardOpenOption.TRUNCATE_EXISTING), bufferSize)) {

                byte[] buffer = new byte[bufferSize];
                long downloaded = 0;
                long startTime = System.currentTimeMillis();
                long lastUpdate = startTime;
                double smoothedSpeed = 0;
                int bytesRead;

                while ((bytesRead = input.read(buffer)) != -1) {
                    output.write(buffer, 0, bytesRead);
                    downloaded += bytesRead;

                    long now = System.currentTimeMillis();
                    if (progressCallback != null && now - lastUpdate >= 50) {
                        long elapsed = now - startTime;
                        if (elapsed > 0) {
                            double currentSpeed = (double) downloaded / (elapsed / 1000.0);
                            smoothedSpeed = smoothedSpeed == 0
                                    ? currentSpeed
                                    : smoothedSpeed * 0.7 + currentSpeed * 0.3;
                        }
                        progressCallback.onProgress(downloaded, contentLength, smoothedSpeed, fileName, "Descarga");
                        lastUpdate = now;
                    }
                }

                if (downloaded == 0) throw new IOException("0 bytes recibidos desde: " + url);

            } catch (IOException e) {
                Files.deleteIfExists(tempFile);
                throw new DownloadException("Error I/O descargando desde " + url + ": " + e.getMessage(), e);
            }

            Path finalPath = targetDir.resolve(fileName);
            Files.move(tempFile, finalPath,
                    StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE);

            System.out.println("[DOWNLOAD] Completado: " + fileName + " (" + formatBytes(Files.size(finalPath)) + ")");
        }
    }

    private static String extractFileName(Response response, String url) {
        String disposition = response.header("Content-Disposition");
        if (disposition != null) {
            for (String part : disposition.split(";")) {
                part = part.trim();
                if (part.startsWith("filename=")) {
                    String name = part.substring("filename=".length())
                            .replaceAll("^\"|\"$", "").trim();
                    if (!name.isEmpty()) return sanitizeFileName(name);
                }
            }
        }
        String path = java.net.URI.create(url).getPath();
        if (path != null && !path.isEmpty()) {
            String name = Path.of(path).getFileName().toString();
            if (!name.isEmpty() && !name.equals("/")) return sanitizeFileName(name);
        }
        return "download-" + Instant.now().toEpochMilli() + ".zip";
    }

    private static String sanitizeFileName(String fileName) {
        return fileName.replaceAll("[\\\\/:*?\"<>|]", "_").trim();
    }

    public static String formatBytes(long bytes) {
        if (bytes < 1024) return bytes + " B";
        int exp = (int) (Math.log(bytes) / Math.log(1024));
        String pre = String.valueOf("KMGTPE".charAt(exp - 1));
        return String.format("%.1f %sB", bytes / Math.pow(1024, exp), pre);
    }

    public static String formatSpeed(double bytesPerSecond) {
        return formatBytes((long) bytesPerSecond) + "/s";
    }
}