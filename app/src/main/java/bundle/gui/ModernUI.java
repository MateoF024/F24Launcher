package bundle.gui;

import bundle.config.InstallerConfig;
import bundle.installer.BundleInstaller;
import bundle.settings.AppSettings;

import javax.imageio.ImageIO;
import javax.swing.*;
import java.awt.*;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.awt.image.BufferedImage;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

public class ModernUI extends JFrame {
    private final BundleInstaller installer;
    private final AppSettings appSettings;

    private CustomTitleBar titleBar;
    private NavigationPanel navigationPanel;
    private JPanel contentPanel;
    private CardLayout cardLayout;

    private ModpacksPanel modpacksPanel;
    private LoadersPanel loadersPanel;
    private SettingsPanel settingsPanel;

    public ModernUI(BundleInstaller installer) {
        this.installer = installer;
        this.appSettings = AppSettings.getInstance();

        System.out.println("[DEBUG] Inicializando ModernUI...");

        initializeFrame();
        loadIcon();
        createComponents();
        layoutComponents();
        setupEventHandlers();
        updateTheme();

        System.out.println("[DEBUG] ModernUI inicializada correctamente");
    }

    private void initializeFrame() {
        System.out.println("[DEBUG] Configurando frame principal...");

        setTitle("Bundle Installer - Modpack & Loader Manager");
        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        setUndecorated(true);
        setResizable(false);
        setSize(900, 600);

        System.out.println("[DEBUG] Frame configurado");
    }

    private void loadIcon() {
        try {
            InputStream iconStream = getClass().getClassLoader().getResourceAsStream("icon.png");
            if (iconStream != null) {
                BufferedImage iconImage = ImageIO.read(iconStream);

                List<Image> icons = new ArrayList<>();
                icons.add(iconImage);
                icons.add(iconImage.getScaledInstance(16, 16, Image.SCALE_SMOOTH));
                icons.add(iconImage.getScaledInstance(32, 32, Image.SCALE_SMOOTH));
                icons.add(iconImage.getScaledInstance(64, 64, Image.SCALE_SMOOTH));
                icons.add(iconImage.getScaledInstance(128, 128, Image.SCALE_SMOOTH));

                setIconImages(icons);

                if (Taskbar.isTaskbarSupported()) {
                    Taskbar taskbar = Taskbar.getTaskbar();
                    if (taskbar.isSupported(Taskbar.Feature.ICON_IMAGE)) {
                        taskbar.setIconImage(iconImage);
                    }
                }

                System.out.println("[DEBUG] Icono cargado exitosamente");
            } else {
                System.err.println("[WARN] No se encontró icon.png en resources");
            }
        } catch (Exception e) {
            System.err.println("[ERROR] Error cargando icono: " + e.getMessage());
            e.printStackTrace();
        }
    }

    private void createComponents() {
        System.out.println("[DEBUG] Creando componentes...");

        titleBar = new CustomTitleBar(this, "Bundle Installer");

        navigationPanel = new NavigationPanel(
                this::showModpacksPanel,
                this::showLoadersPanel,
                this::showSettingsPanel
        );

        cardLayout = new CardLayout();
        contentPanel = new JPanel(cardLayout);
        contentPanel.setOpaque(true);

        modpacksPanel = new ModpacksPanel(installer);
        loadersPanel = new LoadersPanel(installer);
        settingsPanel = new SettingsPanel(installer, this::updateTheme);

        contentPanel.add(modpacksPanel, "MODPACKS");
        contentPanel.add(loadersPanel, "LOADERS");
        contentPanel.add(settingsPanel, "SETTINGS");

        System.out.println("[DEBUG] Componentes creados");
    }

    private void layoutComponents() {
        System.out.println("[DEBUG] Configurando layout...");

        setLayout(new BorderLayout());

        add(titleBar, BorderLayout.NORTH);

        JPanel mainPanel = new JPanel(new BorderLayout());
        mainPanel.add(navigationPanel, BorderLayout.WEST);
        mainPanel.add(contentPanel, BorderLayout.CENTER);

        add(mainPanel, BorderLayout.CENTER);

        System.out.println("[DEBUG] Layout configurado");
    }

    private void setupEventHandlers() {
        System.out.println("[DEBUG] Configurando event handlers...");

        addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent e) {
                System.out.println("[DEBUG] Cerrando aplicación...");
                if (appSettings.isPreserveUserSettings()) {
                    appSettings.saveSettings();
                }
                dispose();
                System.exit(0);
            }
        });
    }

    private void showModpacksPanel() {
        cardLayout.show(contentPanel, "MODPACKS");
        navigationPanel.selectButton("MODPACKS");
    }

    private void showLoadersPanel() {
        cardLayout.show(contentPanel, "LOADERS");
        navigationPanel.selectButton("LOADERS");
    }

    private void showSettingsPanel() {
        cardLayout.show(contentPanel, "SETTINGS");
        navigationPanel.selectButton("SETTINGS");
    }

    public void updateTheme() {
        SwingUtilities.invokeLater(() -> {
            try {
                ThemeManager.Colors colors = ThemeManager.getCurrentColors();

                getContentPane().setBackground(colors.background);

                if (titleBar != null) titleBar.updateTheme();
                if (navigationPanel != null) navigationPanel.updateTheme();
                if (modpacksPanel != null) modpacksPanel.updateTheme();
                if (loadersPanel != null) loadersPanel.updateTheme();
                if (settingsPanel != null) settingsPanel.updateTheme();

                if (contentPanel != null) {
                    contentPanel.setBackground(colors.background);
                    updateComponentsRecursively(contentPanel, colors);
                }

                Component[] components = getContentPane().getComponents();
                for (Component comp : components) {
                    if (comp instanceof JPanel) {
                        updateComponentsRecursively((JPanel) comp, colors);
                    }
                }

                repaint();

            } catch (Exception e) {
                System.err.println("[ERROR] Error actualizando tema: " + e.getMessage());
                e.printStackTrace();
            }
        });
    }

    private void updateComponentsRecursively(Container container, ThemeManager.Colors colors) {
        for (Component comp : container.getComponents()) {
            // Saltar paneles que gestionan su propio tema
            if (comp instanceof ModpacksPanel ||
                    comp instanceof LoadersPanel  ||
                    comp instanceof SettingsPanel ||
                    comp instanceof NavigationPanel) {
                continue;
            }
            if (comp instanceof JPanel panel) {
                panel.setBackground(colors.background);
                updateComponentsRecursively(panel, colors);
            } else if (comp instanceof Container c) {
                updateComponentsRecursively(c, colors);
            }
        }
    }

    public void onConfigLoaded(InstallerConfig config) {
        if (modpacksPanel != null) {
            modpacksPanel.applyConfig(config);
        }
    }

    public void displayWindow() {
        System.out.println("[DEBUG] Mostrando ventana principal...");

        SwingUtilities.invokeLater(() -> {
            try {
                validate();
                setLocationRelativeTo(null);

                showModpacksPanel();

                setVisible(true);
                toFront();
                requestFocus();

                System.out.println("[DEBUG] Estado final - Visible: " + isVisible());

                if (!isVisible()) {
                    System.err.println("[ERROR] La ventana no se pudo hacer visible");
                    SwingUtilities.invokeLater(() -> {
                        JOptionPane.showMessageDialog(null,
                                "Error: No se pudo mostrar la interfaz gráfica.\n" +
                                        "Verifica tu instalación de Java y los drivers gráficos.",
                                "Error de Visualización",
                                JOptionPane.ERROR_MESSAGE);
                        System.exit(1);
                    });
                }

            } catch (Exception e) {
                System.err.println("[ERROR] Error mostrando ventana: " + e.getMessage());
                e.printStackTrace();
            }
        });
    }
}