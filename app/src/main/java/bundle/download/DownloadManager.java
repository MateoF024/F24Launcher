package bundle.download;

import bundle.config.DownloadConfig;

import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.ByteBuffer;
import java.nio.channels.Channels;
import java.nio.channels.ReadableByteChannel;
import java.nio.channels.WritableByteChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

public final class DownloadManager {

    private static final int CONNECT_TIMEOUT = 30_000;
    private static final int READ_TIMEOUT = 60_000;
    private static final int BUFFER_SIZE = 512 * 1024;
    private static final int MAX_RETRIES = 3;
    private static final int RETRY_DELAY_MS = 2000;

    private DownloadManager() { }

    public static List<DownloadException> downloadFilesTo(Path targetDir, DownloadConfig dlConfig, ProgressCallback progressCallback) {
        List<DownloadException> errors = new ArrayList<>();

        for (String url : dlConfig.urls) {
            boolean success = false;
            DownloadException lastException = null;

            for (int attempt = 1; attempt <= MAX_RETRIES; attempt++) {
                try {
                    if (progressCallback != null && attempt == 1) {
                        progressCallback.onPhaseStart("Descarga", 1);
                    }

                    if (attempt > 1) {
                        System.out.println("[DOWNLOAD] Reintento " + attempt + "/" + MAX_RETRIES + " para: " + url);
                        Thread.sleep(RETRY_DELAY_MS * attempt);
                    }

                    downloadTo(targetDir, url, progressCallback);

                    if (progressCallback != null && attempt == 1) {
                        progressCallback.onPhaseComplete("Descarga");
                    }

                    success = true;
                    break;

                } catch (DownloadException | IOException e) {
                    lastException = new DownloadException(
                            "Intento " + attempt + "/" + MAX_RETRIES + " fallido: " + e.getMessage(), e);
                    System.err.println("[DOWNLOAD ERROR] " + lastException.getMessage());

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

            if (!success && lastException != null) {
                System.err.println("[DOWNLOAD] Todos los intentos fallaron para: " + url);
            }
        }
        return errors;
    }

    private static Path downloadTo(Path targetDir, String urlString, ProgressCallback progressCallback)
            throws IOException, DownloadException {

        if (!Files.exists(targetDir)) {
            throw new DownloadException("El directorio de destino no existe: " + targetDir);
        }

        if (!Files.isWritable(targetDir)) {
            throw new DownloadException("No hay permisos de escritura en: " + targetDir);
        }

        URL url = new URL(urlString);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setConnectTimeout(CONNECT_TIMEOUT);
        conn.setReadTimeout(READ_TIMEOUT);
        conn.setInstanceFollowRedirects(true);
        conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36");
        conn.setRequestProperty("Accept", "*/*");
        conn.setRequestProperty("Connection", "keep-alive");

        try {
            int status = conn.getResponseCode();

            if (status == 301 || status == 302 || status == 303 || status == 307 || status == 308) {
                String newUrl = conn.getHeaderField("Location");
                throw new DownloadException("Redirección HTTP " + status + " a: " + newUrl);
            }

            if (status >= 400) {
                throw new DownloadException("HTTP " + status + " (" + conn.getResponseMessage() + ") para: " + urlString);
            }

            long contentLength = conn.getContentLengthLong();
            if (contentLength < 0) {
                System.out.println("[DOWNLOAD] Advertencia: Tamaño de archivo desconocido para " + urlString);
            }

            String fileName = extractFileName(conn, url);
            Path tempFile = Files.createTempFile(targetDir, "dl-", ".part");

            ProgressTracker progressTracker = null;
            if (progressCallback != null) {
                progressTracker = new ProgressTracker(progressCallback, fileName, contentLength);
            }

            try (InputStream inputStream = conn.getInputStream();
                 ReadableByteChannel inputChannel = Channels.newChannel(inputStream);
                 WritableByteChannel outputChannel = Files.newByteChannel(tempFile,
                         StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING)) {

                transferOptimized(inputChannel, outputChannel, progressTracker);
            }

            Path finalPath = targetDir.resolve(fileName);
            Files.move(tempFile, finalPath,
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                    java.nio.file.StandardCopyOption.ATOMIC_MOVE);

            if (progressTracker != null) {
                progressTracker.complete();
            }

            System.out.println("[DOWNLOAD] Completado: " + fileName + " (" + formatBytes(Files.size(finalPath)) + ")");

            return finalPath;

        } catch (IOException e) {
            throw new DownloadException("Error I/O descargando desde " + urlString + ": " + e.getMessage(), e);
        } finally {
            conn.disconnect();
        }
    }

    private static void transferOptimized(ReadableByteChannel input, WritableByteChannel output,
                                          ProgressTracker progressTracker) throws IOException {

        ByteBuffer buffer = ByteBuffer.allocateDirect(BUFFER_SIZE);

        long totalBytesRead = 0;
        int bytesRead;

        while ((bytesRead = input.read(buffer)) != -1) {
            totalBytesRead += bytesRead;
            buffer.flip();

            while (buffer.hasRemaining()) {
                output.write(buffer);
            }

            buffer.clear();

            if (progressTracker != null) {
                progressTracker.updateProgress(bytesRead);
            }
        }

        if (totalBytesRead == 0) {
            throw new IOException("No se descargaron datos (0 bytes recibidos)");
        }
    }

    private static String extractFileName(HttpURLConnection conn, URL url) {
        String contentDisposition = conn.getHeaderField("Content-Disposition");
        if (contentDisposition != null) {
            String[] parts = contentDisposition.split(";");
            for (String part : parts) {
                part = part.trim();
                if (part.startsWith("filename=")) {
                    String fileName = part.substring("filename=".length())
                            .replaceAll("^\"|\"$", "")
                            .trim();
                    if (!fileName.isEmpty()) {
                        return sanitizeFileName(fileName);
                    }
                }
            }
        }

        String urlPath = url.getPath();
        if (urlPath != null && !urlPath.isEmpty()) {
            String fileName = Path.of(urlPath).getFileName().toString();
            if (!fileName.isEmpty() && !fileName.equals("/")) {
                return sanitizeFileName(fileName);
            }
        }

        return "download-" + Instant.now().toEpochMilli() + ".zip";
    }

    private static String sanitizeFileName(String fileName) {
        return fileName.replaceAll("[\\\\/:*?\"<>|]", "_").trim();
    }

    private static class ProgressTracker {
        private final ProgressCallback callback;
        private final String fileName;
        private final long totalBytes;
        private long bytesDownloaded = 0;
        private long startTime;
        private long lastUpdateTime;
        private double lastCalculatedSpeed = 0;

        public ProgressTracker(ProgressCallback callback, String fileName, long totalBytes) {
            this.callback = callback;
            this.fileName = fileName;
            this.totalBytes = totalBytes;
            this.startTime = System.currentTimeMillis();
            this.lastUpdateTime = startTime;
        }

        public void updateProgress(long additionalBytes) {
            bytesDownloaded += additionalBytes;
            long currentTime = System.currentTimeMillis();

            if (currentTime - lastUpdateTime >= 50) {
                long totalTimeDiff = currentTime - startTime;

                if (totalTimeDiff > 0) {
                    double currentSpeed = (double) bytesDownloaded / (totalTimeDiff / 1000.0);

                    if (lastCalculatedSpeed == 0) {
                        lastCalculatedSpeed = currentSpeed;
                    } else {
                        lastCalculatedSpeed = (lastCalculatedSpeed * 0.7) + (currentSpeed * 0.3);
                    }
                }

                if (callback != null) {
                    callback.onProgress(bytesDownloaded, totalBytes, lastCalculatedSpeed, fileName, "Descarga");
                }

                lastUpdateTime = currentTime;
            }
        }

        public void complete() {
        }
    }

    public static String formatBytes(long bytes) {
        if (bytes < 1024) return bytes + " B";
        int exp = (int) (Math.log(bytes) / Math.log(1024));
        String pre = "KMGTPE".charAt(exp - 1) + "";
        return String.format("%.1f %sB", bytes / Math.pow(1024, exp), pre);
    }

    public static String formatSpeed(double bytesPerSecond) {
        return formatBytes((long) bytesPerSecond) + "/s";
    }
}