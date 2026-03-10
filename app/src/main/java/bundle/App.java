package bundle;

import bundle.installer.BundleInstaller;
import bundle.settings.AppSettings;

import com.formdev.flatlaf.FlatDarkLaf;
import com.formdev.flatlaf.FlatLightLaf;

import javax.swing.*;
import java.awt.*;

public class App {

    public static void main(String[] args) {
        System.out.println("[DEBUG] === INICIANDO BUNDLE INSTALLER ===");
        System.out.println("[DEBUG] Java Version: " + System.getProperty("java.version"));
        System.out.println("[DEBUG] OS: " + System.getProperty("os.name"));

        System.setProperty("flatlaf.useWindowDecorations", "false");
        System.setProperty("sun.awt.noerasebackground", "true");
        System.setProperty("sun.java2d.d3d", "false");
        System.setProperty("sun.java2d.noddraw", "true");

        setupLookAndFeel();

        SwingUtilities.invokeLater(() -> {
            try {
                BundleInstaller installer = new BundleInstaller();

                Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                    BundleInstaller.shutdown();
                }));

                installer.openUI();

            } catch (Exception e) {
                System.err.println("Error fatal al inicializar la aplicacion: " + e.getMessage());
                e.printStackTrace();

                JOptionPane.showMessageDialog(null,
                        "Error fatal al inicializar la aplicacion:\n" + e.getMessage(),
                        "Error de Inicializacion",
                        JOptionPane.ERROR_MESSAGE);

                System.exit(1);
            }
        });
    }

    private static void setupLookAndFeel() {
        try {
            AppSettings settings = AppSettings.getInstance();
            applyTheme(settings.isDarkMode());

        } catch (Exception e) {
            System.err.println("Error configurando Look and Feel: " + e.getMessage());

            try {
                UIManager.setLookAndFeel(UIManager.getLookAndFeel());
            } catch (Exception fallbackError) {
                System.err.println("Error con fallback Look and Feel: " + fallbackError.getMessage());
            }
        }
    }

    public static void applyTheme(boolean isDarkMode) {
        try {
            if (isDarkMode) {
                UIManager.setLookAndFeel(new FlatDarkLaf());
                System.out.println("Tema oscuro aplicado");
            } else {
                UIManager.setLookAndFeel(new FlatLightLaf());
                System.out.println("Tema claro aplicado");
            }

            setupCustomUIProperties(isDarkMode);

            for (Window window : Window.getWindows()) {
                SwingUtilities.updateComponentTreeUI(window);
                window.repaint();
            }

        } catch (Exception e) {
            System.err.println("Error aplicando tema: " + e.getMessage());
            e.printStackTrace();
        }
    }

    private static void setupCustomUIProperties(boolean isDarkMode) {
        JFrame.setDefaultLookAndFeelDecorated(false);

        UIManager.put("swing.boldMetal", Boolean.FALSE);

        Font defaultFont = new Font("Segoe UI", Font.PLAIN, 13);
        UIManager.put("Label.font", defaultFont);
        UIManager.put("Button.font", defaultFont);
        UIManager.put("TextField.font", defaultFont);
        UIManager.put("ComboBox.font", defaultFont);
        UIManager.put("CheckBox.font", defaultFont);
        UIManager.put("RadioButton.font", defaultFont);
        UIManager.put("List.font", defaultFont);
        UIManager.put("Table.font", defaultFont);
        UIManager.put("TextArea.font", defaultFont);
        UIManager.put("MenuItem.font", defaultFont);
        UIManager.put("Menu.font", defaultFont);

        if (isDarkMode) {
            UIManager.put("Panel.background", new Color(24, 25, 26));
            UIManager.put("OptionPane.background", new Color(40, 43, 48));
            UIManager.put("OptionPane.messageForeground", new Color(255, 255, 255));
        } else {
            UIManager.put("Panel.background", new Color(248, 249, 250));
            UIManager.put("OptionPane.background", new Color(255, 255, 255));
            UIManager.put("OptionPane.messageForeground", new Color(32, 33, 36));
        }

        UIManager.put("Component.focusWidth", 1);
        UIManager.put("Button.arc", 8);
        UIManager.put("Component.arc", 8);
        UIManager.put("ProgressBar.arc", 8);
        UIManager.put("TextComponent.arc", 8);
        UIManager.put("CheckBox.arc", 4);
        UIManager.put("ScrollBar.showButtons", false);
        UIManager.put("ScrollBar.width", 12);
        UIManager.put("Table.showHorizontalLines", false);
        UIManager.put("Table.showVerticalLines", false);
    }
}