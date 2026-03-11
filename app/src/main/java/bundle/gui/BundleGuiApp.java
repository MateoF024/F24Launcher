package bundle.gui;

import bundle.config.InstallerConfig;
import bundle.installer.BundleInstaller;

public class BundleGuiApp {
    private final ModernUI modernUI;

    public BundleGuiApp(BundleInstaller installer) {
        this.modernUI = new ModernUI(installer);
    }

    public void open() {
        if (modernUI != null) modernUI.displayWindow();
    }

    public void onConfigLoaded(InstallerConfig config) {
        if (modernUI != null) modernUI.onConfigLoaded(config);
    }
}