package bundle.util;

import bundle.download.ProgressCallback;

import java.io.*;
import java.nio.file.*;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.nio.charset.StandardCharsets;

public class StreamingZipProcessor {
    private final Path targetDir;
    private final List<String> allowedFolders;
    private final ProgressCallback progressCallback;
    private final String fileName;
    private final long totalBytes;

    private long bytesProcessed = 0;
    private long filesExtracted = 0;
    private long startTime;
    private long lastUpdateTime;

    public StreamingZipProcessor(Path targetDir, List<String> allowedFolders,
                                 ProgressCallback progressCallback, String fileName, long totalBytes) {
        this.targetDir = targetDir;
        this.allowedFolders = allowedFolders;
        this.progressCallback = progressCallback;
        this.fileName = fileName;
        this.totalBytes = totalBytes;
        this.startTime = System.currentTimeMillis();
        this.lastUpdateTime = startTime;
    }

    public void processStream(InputStream inputStream) throws IOException {
        MemoryManager.MemoryInfo memInfo = MemoryManager.getCurrentMemoryInfo();
        int bufferSize = memInfo.optimalBufferSize;

        try (BufferedInputStream bis = new BufferedInputStream(inputStream, bufferSize);
             CountingInputStream cis = new CountingInputStream(bis);
             ZipInputStream zis = new ZipInputStream(cis, StandardCharsets.UTF_8)) {

            ZipEntry entry;
            byte[] buffer = new byte[bufferSize];

            while ((entry = zis.getNextEntry()) != null) {
                if (!shouldProcessEntry(entry.getName())) {
                    zis.closeEntry();
                    continue;
                }

                Path entryPath = targetDir.resolve(entry.getName());

                if (entryPath.getParent() != null) {
                    Files.createDirectories(entryPath.getParent());
                }

                if (entry.isDirectory()) {
                    Files.createDirectories(entryPath);
                } else {
                    extractFileFromStream(zis, entryPath, buffer);
                    filesExtracted++;
                }

                zis.closeEntry();
                updateProgress(cis.getBytesRead());

                if (MemoryManager.isMemoryPressure()) {
                    MemoryManager.forceGarbageCollection();
                }
            }
        }
    }

    private void extractFileFromStream(ZipInputStream zis, Path targetPath, byte[] buffer) throws IOException {
        try (OutputStream outputStream = Files.newOutputStream(targetPath,
                StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING);
             BufferedOutputStream bos = new BufferedOutputStream(outputStream, buffer.length)) {

            int bytesRead;
            while ((bytesRead = zis.read(buffer)) != -1) {
                bos.write(buffer, 0, bytesRead);
            }
        }
    }

    private void updateProgress(long currentBytes) {
        bytesProcessed = currentBytes;
        long currentTime = System.currentTimeMillis();

        if (currentTime - lastUpdateTime >= 100) {
            long elapsed = currentTime - startTime;
            double speed = elapsed > 0 ? (double) bytesProcessed / (elapsed / 1000.0) : 0;

            if (progressCallback != null) {
                progressCallback.onProgress(bytesProcessed, totalBytes, speed,
                        fileName + " (" + filesExtracted + " archivos extraídos)", "Descarga e instalación");
            }

            lastUpdateTime = currentTime;
        }
    }

    private boolean shouldProcessEntry(String entryName) {
        String normalizedPath = entryName.replaceAll("^[/\\\\]+", "").replace('\\', '/');

        if (!normalizedPath.contains("/")) {
            return true;
        }

        String rootFolder = normalizedPath.split("/")[0];
        return allowedFolders.contains(rootFolder);
    }

    private static class CountingInputStream extends FilterInputStream {
        private long bytesRead = 0;

        protected CountingInputStream(InputStream in) {
            super(in);
        }

        @Override
        public int read() throws IOException {
            int b = super.read();
            if (b != -1) bytesRead++;
            return b;
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            int count = super.read(b, off, len);
            if (count > 0) bytesRead += count;
            return count;
        }

        public long getBytesRead() {
            return bytesRead;
        }
    }
}