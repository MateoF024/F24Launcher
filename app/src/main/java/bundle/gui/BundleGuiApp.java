package bundle.gui;

import bundle.installer.BundleInstaller;

public class BundleGuiApp {
    private final ModernUI modernUI;

    public BundleGuiApp(BundleInstaller installer) {
        System.out.println("[DEBUG] Creando BundleGuiApp...");
        this.modernUI = new ModernUI(installer);
        System.out.println("[DEBUG] ModernUI creada en BundleGuiApp");
    }

    public void open() {
        System.out.println("[DEBUG] BundleGuiApp.open() llamado");
        if (modernUI != null) {
            modernUI.displayWindow();
            System.out.println("[DEBUG] ModernUI.displayWindow() ejecutado");
        } else {
            System.err.println("[ERROR] ModernUI es null en BundleGuiApp.open()");
        }
    }
}