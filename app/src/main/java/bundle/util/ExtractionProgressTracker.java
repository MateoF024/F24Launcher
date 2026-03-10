package bundle.util;

import bundle.download.ProgressCallback;

import java.util.concurrent.atomic.AtomicLong;

public class ExtractionProgressTracker {
    private final ProgressCallback callback;
    private final String phaseName;
    private final AtomicLong totalFiles;
    private final AtomicLong extractedFiles;
    private final long startTime;
    private volatile long lastUpdateTime;

    public ExtractionProgressTracker(ProgressCallback callback, String phaseName, long totalFiles) {
        this.callback = callback;
        this.phaseName = phaseName;
        this.totalFiles = new AtomicLong(totalFiles);
        this.extractedFiles = new AtomicLong(0);
        this.startTime = System.currentTimeMillis();
        this.lastUpdateTime = startTime;

        if (callback != null) {
            callback.onPhaseStart(phaseName, totalFiles);
        }
    }

    public void updateProgress(String fileName) {
        long completed = extractedFiles.incrementAndGet();
        long currentTime = System.currentTimeMillis();

        if (currentTime - lastUpdateTime >= 100 || completed == totalFiles.get()) {
            double filesPerSecond = 0;
            long elapsed = currentTime - startTime;
            if (elapsed > 0) {
                filesPerSecond = (double) completed / (elapsed / 1000.0);
            }

            if (callback != null) {
                callback.onProgress(completed, totalFiles.get(), filesPerSecond, fileName, phaseName);
            }

            lastUpdateTime = currentTime;
        }
    }

    public void complete() {
        if (callback != null) {
            callback.onPhaseComplete(phaseName);
        }
    }

    public void updateTotalFiles(long newTotal) {
        totalFiles.set(newTotal);
    }
}