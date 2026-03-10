package bundle.util;

import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;

public class MemoryManager {
    private static final long MIN_BUFFER_SIZE = 128 * 1024;
    private static final long MAX_BUFFER_SIZE = 16 * 1024 * 1024;
    private static final double MEMORY_USAGE_THRESHOLD = 0.85;

    private static final MemoryMXBean memoryBean = ManagementFactory.getMemoryMXBean();

    public static class MemoryInfo {
        public final long totalMemory;
        public final long usedMemory;
        public final long freeMemory;
        public final double usageRatio;
        public final int optimalBufferSize;

        MemoryInfo(long total, long used, long free, double usage, int buffer) {
            this.totalMemory = total;
            this.usedMemory = used;
            this.freeMemory = free;
            this.usageRatio = usage;
            this.optimalBufferSize = buffer;
        }
    }

    public static MemoryInfo getCurrentMemoryInfo() {
        Runtime runtime = Runtime.getRuntime();
        long totalMemory = runtime.totalMemory();
        long freeMemory = runtime.freeMemory();
        long usedMemory = totalMemory - freeMemory;
        long maxMemory = runtime.maxMemory();

        double usageRatio = (double) usedMemory / maxMemory;
        int optimalBufferSize = calculateOptimalBufferSize(maxMemory, usageRatio);

        return new MemoryInfo(maxMemory, usedMemory, maxMemory - usedMemory, usageRatio, optimalBufferSize);
    }

    public static int calculateOptimalBufferSize(long availableMemory, double currentUsage) {
        if (currentUsage > MEMORY_USAGE_THRESHOLD) {
            return (int) MIN_BUFFER_SIZE;
        }

        long baseBuffer = availableMemory / 512;
        baseBuffer = Math.max(MIN_BUFFER_SIZE, Math.min(MAX_BUFFER_SIZE, baseBuffer));

        if (availableMemory > 4L * 1024 * 1024 * 1024) {
            baseBuffer = Math.min(MAX_BUFFER_SIZE, baseBuffer);
        } else if (availableMemory > 2L * 1024 * 1024 * 1024) {
            baseBuffer = Math.min(8 * 1024 * 1024, baseBuffer);
        } else if (availableMemory > 1024 * 1024 * 1024) {
            baseBuffer = Math.min(4 * 1024 * 1024, baseBuffer);
        } else {
            baseBuffer = Math.min(1024 * 1024, baseBuffer);
        }

        return (int) baseBuffer;
    }

    public static boolean shouldUseStreamingMode(long fileSize) {
        MemoryInfo info = getCurrentMemoryInfo();
        return fileSize > (info.freeMemory / 3) || fileSize > 100 * 1024 * 1024;
    }

    public static void forceGarbageCollection() {
        System.gc();
        try {
            Thread.sleep(25);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    public static boolean isMemoryPressure() {
        return getCurrentMemoryInfo().usageRatio > MEMORY_USAGE_THRESHOLD;
    }
}