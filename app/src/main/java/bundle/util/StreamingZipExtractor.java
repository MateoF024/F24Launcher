package bundle.util;

import java.io.*;
import java.nio.channels.Channels;
import java.nio.channels.FileChannel;
import java.nio.channels.ReadableByteChannel;
import java.nio.file.*;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipInputStream;
import java.nio.charset.StandardCharsets;

public class StreamingZipExtractor {

    public static void extractWithProgress(Path zipFilePath, Path targetDir,
                                           List<String> foldersToClean,
                                           ExtractionProgressTracker tracker) throws IOException {

        MemoryManager.MemoryInfo memInfo = MemoryManager.getCurrentMemoryInfo();
        long zipSize = Files.size(zipFilePath);

        if (MemoryManager.shouldUseStreamingMode(zipSize)) {
            extractUsingStreaming(zipFilePath, targetDir, foldersToClean, tracker, memInfo.optimalBufferSize);
        } else {
            extractUsingZipFile(zipFilePath, targetDir, foldersToClean, tracker, memInfo.optimalBufferSize);
        }

        if (MemoryManager.isMemoryPressure()) {
            MemoryManager.forceGarbageCollection();
        }
    }

    private static void extractUsingStreaming(Path zipFilePath, Path targetDir,
                                              List<String> foldersToClean,
                                              ExtractionProgressTracker tracker,
                                              int bufferSize) throws IOException {

        try (FileInputStream fis = new FileInputStream(zipFilePath.toFile());
             BufferedInputStream bis = new BufferedInputStream(fis, bufferSize);
             ZipInputStream zis = new ZipInputStream(bis, StandardCharsets.UTF_8)) {

            ZipEntry entry;
            byte[] buffer = new byte[bufferSize];

            while ((entry = zis.getNextEntry()) != null) {
                if (!shouldProcessEntry(entry.getName(), foldersToClean, targetDir)) {
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
                    extractFileFromStream(zis, entryPath, buffer, entry.getSize());
                    tracker.updateProgress(entry.getName());
                }

                zis.closeEntry();

                if (MemoryManager.isMemoryPressure()) {
                    MemoryManager.forceGarbageCollection();
                    MemoryManager.MemoryInfo newInfo = MemoryManager.getCurrentMemoryInfo();
                    if (newInfo.optimalBufferSize != bufferSize && newInfo.optimalBufferSize > 0) {
                        buffer = new byte[newInfo.optimalBufferSize];
                        bufferSize = newInfo.optimalBufferSize;
                    }
                }
            }
        }
    }

    private static void extractUsingZipFile(Path zipFilePath, Path targetDir,
                                            List<String> foldersToClean,
                                            ExtractionProgressTracker tracker,
                                            int bufferSize) throws IOException {

        try (ZipFile zipFile = new ZipFile(zipFilePath.toFile(), StandardCharsets.UTF_8)) {
            zipFile.stream()
                    .filter(ZipEntry::isDirectory)
                    .filter(entry -> shouldProcessEntry(entry.getName(), foldersToClean, targetDir))
                    .forEach(entry -> {
                        try {
                            Path entryPath = targetDir.resolve(entry.getName());
                            Files.createDirectories(entryPath);
                        } catch (IOException e) {
                            System.err.println("Error creando directorio: " + entry.getName());
                        }
                    });

            zipFile.stream()
                    .filter(entry -> !entry.isDirectory())
                    .filter(entry -> shouldProcessEntry(entry.getName(), foldersToClean, targetDir))
                    .forEach(entry -> {
                        try {
                            Path entryPath = targetDir.resolve(entry.getName());

                            if (entryPath.getParent() != null) {
                                Files.createDirectories(entryPath.getParent());
                            }

                            if (entry.getSize() > 100 * 1024 * 1024) {
                                extractLargeFileOptimized(zipFile, entry, entryPath, bufferSize);
                            } else {
                                extractSmallFileOptimized(zipFile, entry, entryPath, bufferSize);
                            }

                            tracker.updateProgress(entry.getName());

                        } catch (IOException e) {
                            System.err.println("Error extrayendo: " + entry.getName());
                        }
                    });
        }
    }

    private static void extractFileFromStream(ZipInputStream zis, Path targetPath, byte[] buffer, long fileSize) throws IOException {
        try (OutputStream outputStream = Files.newOutputStream(targetPath,
                StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING);
             BufferedOutputStream bos = new BufferedOutputStream(outputStream, buffer.length)) {

            int bytesRead;
            while ((bytesRead = zis.read(buffer)) != -1) {
                bos.write(buffer, 0, bytesRead);
            }
        }
    }

    private static void extractLargeFileOptimized(ZipFile zipFile, ZipEntry entry, Path targetPath, int bufferSize) throws IOException {
        try (InputStream inputStream = zipFile.getInputStream(entry);
             ReadableByteChannel inputChannel = Channels.newChannel(inputStream);
             FileChannel outputChannel = FileChannel.open(targetPath,
                     StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING)) {

            long transferred = 0;
            long size = entry.getSize();
            long chunkSize = Math.min(bufferSize * 8L, 32 * 1024 * 1024);

            while (transferred < size) {
                long count = outputChannel.transferFrom(inputChannel, transferred,
                        Math.min(chunkSize, size - transferred));
                if (count <= 0) break;
                transferred += count;

                if (transferred % (chunkSize * 4) == 0 && MemoryManager.isMemoryPressure()) {
                    MemoryManager.forceGarbageCollection();
                }
            }
        }
    }

    private static void extractSmallFileOptimized(ZipFile zipFile, ZipEntry entry, Path targetPath, int bufferSize) throws IOException {
        try (InputStream inputStream = zipFile.getInputStream(entry);
             BufferedInputStream bis = new BufferedInputStream(inputStream, bufferSize);
             OutputStream outputStream = Files.newOutputStream(targetPath,
                     StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING);
             BufferedOutputStream bos = new BufferedOutputStream(outputStream, bufferSize)) {

            byte[] buffer = new byte[bufferSize];
            int bytesRead;
            while ((bytesRead = bis.read(buffer)) != -1) {
                bos.write(buffer, 0, bytesRead);
            }
        }
    }

    private static boolean shouldProcessEntry(String entryName, List<String> foldersToClean, Path targetDir) {
        String normalizedPath = entryName.replaceAll("^[/\\\\]+", "").replace('\\', '/');

        if (!normalizedPath.contains("/")) {
            return true;
        }

        String rootFolder = normalizedPath.split("/")[0].toLowerCase();

        for (String folderToClean : foldersToClean) {
            if (rootFolder.equals(folderToClean.toLowerCase())) {
                Path folderPath = targetDir.resolve(rootFolder);
                if (Files.exists(folderPath)) {
                    return true;
                }
                return true;
            }
        }

        return true;
    }

    public static boolean shouldProcessEntry(String entryName, List<String> allowedFolders) {
        return true;
    }
}