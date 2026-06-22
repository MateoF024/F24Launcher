package f24launcher.util;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

public final class AppExecutors {

    private static final ExecutorService IO = Executors.newFixedThreadPool(4);
    private static final ExecutorService DOWNLOAD = Executors.newFixedThreadPool(6);

    private AppExecutors() {}

    public static ExecutorService io() { return IO; }

    public static ExecutorService download() { return DOWNLOAD; }

    public static void shutdownAll() {
        IO.shutdown();
        DOWNLOAD.shutdown();
        try {
            IO.awaitTermination(3, TimeUnit.SECONDS);
            DOWNLOAD.awaitTermination(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
