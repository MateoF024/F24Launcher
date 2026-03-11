package bundle.gui;

import bundle.installer.BundleInstaller;
import bundle.config.RemoteConfigLoader;
import bundle.config.ConfigParser;
import bundle.config.InstallerConfig;
import bundle.download.DownloadException;
import bundle.download.ProgressCallback;
import bundle.settings.AppSettings;
import com.google.gson.Gson;
import com.google.gson.JsonObject;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;

public class ModpacksPanel extends JPanel {
    private final BundleInstaller installer;

    private enum PanelState { MAIN, PROGRESS, RESULT }
    private PanelState currentState = PanelState.MAIN;

    private JPanel mainPanel;

    // Componentes compartidos via GuiFactory
    private GuiFactory.ProgressPanelComponents progressComponents;
    private GuiFactory.ResultPanelComponents resultComponents;

    private JComboBox<String> modpackCombo;
    private JButton refreshButton;
    private JTextField pathField;
    private JButton browseButton;
    private JButton installButton;

    public ModpacksPanel(BundleInstaller installer) {
        this.installer = installer;
        setLayout(new CardLayout());
        setOpaque(false);

        createComponents();
        setupEventHandlers();
        updateTheme();
        showMainPanel();
    }

    private void createComponents() {
        createMainPanel();

        progressComponents = GuiFactory.createProgressPanel();
        resultComponents   = GuiFactory.createResultPanel(this::showMainPanel);

        add(mainPanel,                    "MAIN");
        add(progressComponents.panel,     "PROGRESS");
        add(resultComponents.panel,       "RESULT");
    }

    private void createMainPanel() {
        mainPanel = new JPanel(new GridBagLayout());
        mainPanel.setOpaque(false);
        mainPanel.setBorder(new EmptyBorder(30, 40, 30, 40));

        JPanel center = new JPanel(new GridBagLayout());
        center.setOpaque(false);

        GridBagConstraints gbc = new GridBagConstraints();
        gbc.insets = new Insets(12, 8, 12, 8);
        gbc.anchor = GridBagConstraints.WEST;

        // Modpack
        gbc.gridx = 0; gbc.gridy = 0;
        JLabel modpackLabel = new JLabel("Modpack:");
        modpackLabel.setFont(ThemeManager.getBoldFont());
        center.add(modpackLabel, gbc);

        InstallerConfig config = installer.getInstallerConfig();
        String[] names = config.configNames.isEmpty()
                ? new String[]{"Sin modpacks disponibles"}
                : config.configNames.toArray(new String[0]);

        gbc.gridx = 1; gbc.weightx = 1.0; gbc.fill = GridBagConstraints.HORIZONTAL;
        modpackCombo = GuiFactory.createStyledComboBox(names);
        center.add(modpackCombo, gbc);

        gbc.gridx = 2; gbc.weightx = 0; gbc.fill = GridBagConstraints.NONE;
        refreshButton = GuiFactory.createStyledButton("Actualizar", 100, 36);
        center.add(refreshButton, gbc);

        // Directorio
        gbc.gridx = 0; gbc.gridy = 1;
        JLabel pathLabel = new JLabel("Directorio:");
        pathLabel.setFont(ThemeManager.getBoldFont());
        center.add(pathLabel, gbc);

        gbc.gridx = 1; gbc.weightx = 1.0; gbc.fill = GridBagConstraints.HORIZONTAL;
        pathField = GuiFactory.createStyledTextField(installer.getGameDir().toString());
        center.add(pathField, gbc);

        gbc.gridx = 2; gbc.weightx = 0; gbc.fill = GridBagConstraints.NONE;
        browseButton = GuiFactory.createStyledButton("Buscar", 100, 36);
        center.add(browseButton, gbc);

        // Instalar
        gbc.gridx = 0; gbc.gridy = 2; gbc.gridwidth = 3;
        gbc.anchor = GridBagConstraints.CENTER;
        gbc.insets = new Insets(20, 8, 8, 8);
        installButton = GuiFactory.createStyledButton("Instalar", 140, 45);
        center.add(installButton, gbc);

        GridBagConstraints mainGbc = new GridBagConstraints();
        mainGbc.gridx = 0; mainGbc.gridy = 0;
        mainGbc.weightx = 1.0; mainGbc.weighty = 1.0;
        mainGbc.anchor = GridBagConstraints.CENTER;
        mainPanel.add(center, mainGbc);
    }

    private void setupEventHandlers() {
        modpackCombo.addActionListener(e -> {
            String selected = (String) modpackCombo.getSelectedItem();
            if (selected != null) installer.selectedInstall = selected;
        });

        refreshButton.addActionListener(e -> refreshModpacks());
        browseButton.addActionListener(e -> browseDirectory());
        installButton.addActionListener(e -> performInstall());
    }

    public void applyConfig(InstallerConfig config) {
        if (config == null || config.configNames.isEmpty()) return;

        installer.updateInstallerConfig(config);
        installer.selectedInstall = config.configNames.get(0);

        DefaultComboBoxModel<String> model = new DefaultComboBoxModel<>(
                config.configNames.toArray(new String[0]));
        modpackCombo.setModel(model);
        modpackCombo.setSelectedIndex(0);
    }

    private void refreshModpacks() {
        refreshButton.setEnabled(false);
        refreshButton.setText("Actualizando...");

        SwingWorker<InstallerConfig, Void> worker = new SwingWorker<>() {
            @Override
            protected InstallerConfig doInBackground() {
                try {
                    JsonObject configObject = RemoteConfigLoader.loadAndValidateRemoteConfig();
                    if (configObject != null) return ConfigParser.parse(configObject);
                } catch (Exception e) {
                    System.err.println("Error al cargar configuración remota: " + e.getMessage());
                }
                InputStream configStream = BundleInstaller.class.getClassLoader()
                        .getResourceAsStream("installer_config.json");
                if (configStream != null) {
                    try {
                        InputStreamReader reader = new InputStreamReader(configStream, StandardCharsets.UTF_8);
                        JsonObject configObject = new Gson().fromJson(reader, JsonObject.class);
                        return ConfigParser.parse(configObject);
                    } catch (Exception e) {
                        System.err.println("Error al parsear configuración local: " + e.getMessage());
                    }
                }
                return null;
            }

            @Override
            protected void done() {
                refreshButton.setEnabled(true);
                refreshButton.setText("Actualizar");
                try {
                    InstallerConfig newConfig = get();
                    if (newConfig != null && !newConfig.configNames.isEmpty()) {
                        applyConfig(newConfig);
                    }
                } catch (Exception e) {
                    System.err.println("Error actualizando combo de modpacks: " + e.getMessage());
                }
            }
        };
        worker.execute();
    }

    private void browseDirectory() {
        JFileChooser chooser = new JFileChooser();
        chooser.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
        chooser.setCurrentDirectory(installer.getGameDir().toFile());
        chooser.setDialogTitle("Seleccionar directorio de instalación");

        if (chooser.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) {
            Path selected = chooser.getSelectedFile().toPath();
            installer.setGameDir(selected);
            AppSettings.getInstance().setLastGameDir(selected.toString());
            pathField.setText(selected.toString());
        }
    }

    private void performInstall() {
        showProgressPanel();
        progressComponents.reset();

        ProgressCallback callback = new ProgressCallback() {
            @Override
            public void onPhaseStart(String phaseName, long totalItems) {
                SwingUtilities.invokeLater(() -> {
                    progressComponents.statusLabel.setText("Iniciando " + phaseName.toLowerCase() + "...");
                    progressComponents.progressBar.setValue(0);
                    progressComponents.progressLabel.setText("0% - Iniciando " + phaseName.toLowerCase() + "...");
                });
            }

            @Override
            public void onProgress(long itemsCompleted, long totalItems, double speed,
                                   String currentItem, String phaseName) {
                final int pct = totalItems > 0 ? (int) ((itemsCompleted * 100) / totalItems) : 0;
                SwingUtilities.invokeLater(() -> {
                    progressComponents.progressBar.setValue(Math.min(100, pct));
                    if ("Descarga".equals(phaseName)) {
                        String speedText = formatSpeed(speed);
                        String sizeText  = formatBytes(itemsCompleted);
                        String totalText = totalItems > 0 ? formatBytes(totalItems) : "Desconocido";
                        progressComponents.progressLabel.setText(
                                String.format("%d%% - %s (%s/%s)", pct, speedText, sizeText, totalText));
                        progressComponents.statusLabel.setText("Descargando: " + currentItem);
                    } else if ("Extracción".equals(phaseName)) {
                        progressComponents.progressLabel.setText(
                                String.format("%d%% - %.1f archivos/s (%d/%d archivos)",
                                        pct, speed, itemsCompleted, totalItems));
                        progressComponents.statusLabel.setText("Extrayendo: " + currentItem);
                    }
                });
            }

            @Override
            public void onPhaseComplete(String phaseName) {
                SwingUtilities.invokeLater(() ->
                        progressComponents.statusLabel.setText(phaseName + " completada"));
            }

            @Override
            public void onAllComplete() {
                SwingUtilities.invokeLater(() -> {
                    progressComponents.statusLabel.setText("¡Instalación completada!");
                    progressComponents.progressBar.setValue(100);
                    progressComponents.progressLabel.setText("100% - ¡Completado!");
                });
            }
        };

        SwingWorker<Boolean, Void> worker = new SwingWorker<>() {
            private String errorMessage = null;

            @Override
            protected Boolean doInBackground() {
                try {
                    SwingUtilities.invokeLater(() -> {
                        progressComponents.statusLabel.setText("Preparando instalación...");
                        progressComponents.progressBar.setValue(0);
                        progressComponents.progressLabel.setText("0% - Preparando...");
                    });

                    installer.install(callback);

                    SwingUtilities.invokeLater(() -> {
                        progressComponents.statusLabel.setText("Procesando archivos descargados...");
                        progressComponents.progressBar.setValue(95);
                        progressComponents.progressLabel.setText("95% - Finalizando...");
                    });

                    Thread.sleep(500);

                    SwingUtilities.invokeLater(() -> {
                        progressComponents.statusLabel.setText("¡Instalación completada!");
                        progressComponents.progressBar.setValue(100);
                        progressComponents.progressLabel.setText("100% - ¡Completado!");
                    });

                    Thread.sleep(1000);
                    return true;

                } catch (IOException | DownloadException e) {
                    e.printStackTrace();
                    errorMessage = buildDetailedErrorMessage(e);
                    return false;
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return false;
                }
            }

            @Override
            protected void done() {
                try {
                    if (get()) {
                        showResult("¡La instalación de " + installer.selectedInstall +
                                        " fue completada exitosamente!",
                                ThemeManager.getCurrentColors().success);
                    } else {
                        showResult(errorMessage != null ? errorMessage : "Error desconocido", Color.RED);
                    }
                } catch (Exception ex) {
                    showResult("Error inesperado: " + ex.getMessage(), Color.RED);
                }
            }
        };
        worker.execute();
    }

    private String buildDetailedErrorMessage(Exception e) {
        StringBuilder msg = new StringBuilder("Error durante la instalación:\n\n");
        if (e.getMessage() != null && e.getMessage().contains("Errores durante la descarga")) {
            msg.append("No se pudo descargar el modpack.\n\n");
            msg.append("Posibles causas:\n");
            msg.append("• Conexión a internet inestable\n");
            msg.append("• Firewall/Antivirus bloqueando la descarga\n");
            msg.append("• Servidor temporalmente no disponible\n");
            msg.append("• Sin permisos de escritura en el directorio\n\n");
            msg.append("Soluciones:\n");
            msg.append("1. Verifica tu conexión a internet\n");
            msg.append("2. Desactiva temporalmente el antivirus\n");
            msg.append("3. Ejecuta el instalador como administrador\n");
            msg.append("4. Intenta de nuevo en unos minutos");
        } else {
            msg.append(e.getMessage());
        }
        return msg.toString();
    }

    private String formatBytes(long bytes) {
        if (bytes < 1024) return bytes + " B";
        int exp = (int) (Math.log(bytes) / Math.log(1024));
        String pre = String.valueOf("KMGTPE".charAt(exp - 1));
        return String.format("%.1f %sB", bytes / Math.pow(1024, exp), pre);
    }

    private String formatSpeed(double bytesPerSecond) {
        return formatBytes((long) bytesPerSecond) + "/s";
    }

    private void showMainPanel() {
        currentState = PanelState.MAIN;
        ((CardLayout) getLayout()).show(this, "MAIN");
    }

    private void showProgressPanel() {
        currentState = PanelState.PROGRESS;
        ((CardLayout) getLayout()).show(this, "PROGRESS");
    }

    private void showResult(String message, Color color) {
        currentState = PanelState.RESULT;
        resultComponents.show(message, color);
        ((CardLayout) getLayout()).show(this, "RESULT");
    }

    public void updateTheme() {
        ThemeManager.Colors colors = ThemeManager.getCurrentColors();

        updatePanelTheme(mainPanel, colors);
        updatePanelTheme(progressComponents.panel, colors);
        updatePanelTheme(resultComponents.panel, colors);

        if (modpackCombo != null) {
            modpackCombo.setBackground(colors.fieldBackground);
            modpackCombo.setForeground(colors.text);
        }
        if (pathField != null) {
            pathField.setBackground(colors.fieldBackground);
            pathField.setForeground(colors.text);
            pathField.setCaretColor(colors.text);
        }
        if (progressComponents != null) {
            progressComponents.statusLabel.setForeground(colors.text);
            progressComponents.progressLabel.setForeground(new Color(180, 180, 180));
        }

        repaint();
    }

    private void updatePanelTheme(Component comp, ThemeManager.Colors colors) {
        if (comp instanceof JLabel) {
            comp.setForeground(colors.text);
        } else if (comp instanceof Container c) {
            for (Component child : c.getComponents()) {
                updatePanelTheme(child, colors);
            }
        }
    }
}