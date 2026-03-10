package bundle.download;

public interface ProgressCallback {
    void onPhaseStart(String phaseName, long totalItems);
    void onProgress(long itemsCompleted, long totalItems, double speed, String currentItem, String phaseName);
    void onPhaseComplete(String phaseName);
    void onAllComplete();
}