package bundle.gui;

import bundle.installer.BundleInstaller;
import bundle.config.RemoteConfigLoader;
import bundle.config.ConfigParser;
import bundle.config.InstallerConfig;
import bundle.download.DownloadException;
import bundle.download.DownloadManager;
import bundle.download.ProgressCallback;
import com.google.gson.Gson;
import com.google.gson.JsonObject;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

public class ModpacksPanel extends JPanel {
    private final BundleInstaller installer;

    private enum PanelState {
        MAIN, PROGRESS, RESULT
    }
    private PanelState currentState = PanelState.MAIN;

    private JPanel mainPanel;
    private JPanel progressPanel;
    private JPanel resultPanel;

    private JComboBox<String> modpackCombo;
    private JButton refreshButton;
    private JTextField pathField;
    private JButton browseButton;
    private JButton installButton;

    private JProgressBar progressBar;
    private JLabel statusLabel;
    private JLabel progressLabel;

    private JLabel resultLabel;
    private JButton backButton;

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
        createProgressPanel();
        createResultPanel();

        add(mainPanel, "MAIN");
        add(progressPanel, "PROGRESS");
        add(resultPanel, "RESULT");
    }

    private void createMainPanel() {
        mainPanel = new JPanel(new GridBagLayout());
        mainPanel.setOpaque(false);
        mainPanel.setBorder(new EmptyBorder(30, 40, 30, 40));

        JPanel centerContainer = new JPanel(new GridBagLayout());
        centerContainer.setOpaque(false);

        GridBagConstraints gbc = new GridBagConstraints();
        gbc.insets = new Insets(12, 8, 12, 8);
        gbc.anchor = GridBagConstraints.WEST;

        gbc.gridx = 0; gbc.gridy = 0;
        JLabel modpackLabel = new JLabel("Modpack:");
        modpackLabel.setFont(ThemeManager.getBoldFont());
        centerContainer.add(modpackLabel, gbc);

        gbc.gridx = 1; gbc.weightx = 1.0; gbc.fill = GridBagConstraints.HORIZONTAL;
        modpackCombo = createStyledComboBox(installer.installerConfig.configNames.toArray(new String[0]));
        centerContainer.add(modpackCombo, gbc);

        gbc.gridx = 2; gbc.weightx = 0; gbc.fill = GridBagConstraints.NONE;
        refreshButton = createStyledButton("Actualizar", 100, 36);
        centerContainer.add(refreshButton, gbc);

        gbc.gridx = 0; gbc.gridy = 1;
        JLabel pathLabel = new JLabel("Directorio:");
        pathLabel.setFont(ThemeManager.getBoldFont());
        centerContainer.add(pathLabel, gbc);

        gbc.gridx = 1; gbc.weightx = 1.0; gbc.fill = GridBagConstraints.HORIZONTAL;
        pathField = createStyledTextField(installer.gameDir.toString());
        centerContainer.add(pathField, gbc);

        gbc.gridx = 2; gbc.weightx = 0; gbc.fill = GridBagConstraints.NONE;
        browseButton = createStyledButton("Buscar", 100, 36);
        centerContainer.add(browseButton, gbc);

        gbc.gridx = 0; gbc.gridy = 2; gbc.gridwidth = 3;
        gbc.anchor = GridBagConstraints.CENTER;
        gbc.insets = new Insets(20, 8, 8, 8);
        installButton = createStyledButton("Instalar", 140, 45);
        centerContainer.add(installButton, gbc);

        GridBagConstraints mainGbc = new GridBagConstraints();
        mainGbc.gridx = 0; mainGbc.gridy = 0;
        mainGbc.weightx = 1.0; mainGbc.weighty = 1.0;
        mainGbc.anchor = GridBagConstraints.CENTER;
        mainPanel.add(centerContainer, mainGbc);
    }

    private void createProgressPanel() {
        progressPanel = new JPanel(new GridBagLayout());
        progressPanel.setOpaque(false);
        progressPanel.setBorder(new EmptyBorder(40, 40, 40, 40));

        JPanel content = new JPanel();
        content.setLayout(new BoxLayout(content, BoxLayout.Y_AXIS));
        content.setOpaque(false);

        statusLabel = new JLabel("Preparando instalación...", SwingConstants.CENTER);
        statusLabel.setFont(ThemeManager.getNormalFont());
        statusLabel.setAlignmentX(Component.CENTER_ALIGNMENT);
        content.add(statusLabel);

        content.add(Box.createVerticalStrut(20));

        progressBar = createStyledProgressBar();
        progressBar.setAlignmentX(Component.CENTER_ALIGNMENT);
        content.add(progressBar);

        content.add(Box.createVerticalStrut(12));

        progressLabel = new JLabel("0%", SwingConstants.CENTER);
        progressLabel.setFont(ThemeManager.getSmallFont());
        progressLabel.setAlignmentX(Component.CENTER_ALIGNMENT);
        content.add(progressLabel);

        GridBagConstraints gbc = new GridBagConstraints();
        gbc.gridx = 0; gbc.gridy = 0;
        gbc.weightx = 1.0; gbc.weighty = 1.0;
        gbc.anchor = GridBagConstraints.CENTER;
        progressPanel.add(content, gbc);
    }

    private void createResultPanel() {
        resultPanel = new JPanel(new GridBagLayout());
        resultPanel.setOpaque(false);
        resultPanel.setBorder(new EmptyBorder(40, 40, 40, 40));

        JPanel content = new JPanel();
        content.setLayout(new BoxLayout(content, BoxLayout.Y_AXIS));
        content.setOpaque(false);

        resultLabel = new JLabel("", SwingConstants.CENTER);
        resultLabel.setFont(ThemeManager.getBoldFont());
        resultLabel.setAlignmentX(Component.CENTER_ALIGNMENT);
        content.add(resultLabel);

        content.add(Box.createVerticalStrut(25));

        backButton = createStyledButton("Volver", 120, 40);
        backButton.setAlignmentX(Component.CENTER_ALIGNMENT);
        content.add(backButton);

        GridBagConstraints gbc = new GridBagConstraints();
        gbc.gridx = 0; gbc.gridy = 0;
        gbc.weightx = 1.0; gbc.weighty = 1.0;
        gbc.anchor = GridBagConstraints.CENTER;
        resultPanel.add(content, gbc);
    }

    private JComboBox<String> createStyledComboBox(String[] items) {
        JComboBox<String> combo = new JComboBox<>(items);
        combo.setFont(ThemeManager.getNormalFont());
        combo.setPreferredSize(new Dimension(280, 36));
        combo.setRenderer(new DefaultListCellRenderer() {
            @Override
            public Component getListCellRendererComponent(JList<?> list, Object value, int index,
                                                          boolean isSelected, boolean cellHasFocus) {
                JLabel label = (JLabel) super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus);
                label.setBorder(new EmptyBorder(6, 8, 6, 8));
                label.setFont(ThemeManager.getNormalFont());
                label.setOpaque(true);

                ThemeManager.Colors colors = ThemeManager.getCurrentColors();
                if (isSelected) {
                    label.setBackground(colors.primary.darker());
                    label.setForeground(Color.WHITE);
                } else {
                    label.setBackground(colors.fieldBackground);
                    label.setForeground(colors.text);
                }
                return label;
            }
        });
        return combo;
    }

    private JTextField createStyledTextField(String text) {
        JTextField field = new JTextField(text) {
            @Override
            protected void paintComponent(Graphics g) {
                Graphics2D g2 = (Graphics2D) g.create();
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

                ThemeManager.Colors colors = ThemeManager.getCurrentColors();

                g2.setColor(colors.fieldBackground);
                g2.fillRoundRect(0, 0, getWidth(), getHeight(), 8, 8);

                g2.setColor(hasFocus() ? colors.primary : colors.border);
                g2.setStroke(new BasicStroke(hasFocus() ? 2 : 1));
                g2.drawRoundRect(0, 0, getWidth() - 1, getHeight() - 1, 8, 8);

                g2.dispose();
                super.paintComponent(g);
            }
        };

        field.setFont(ThemeManager.getNormalFont());
        field.setPreferredSize(new Dimension(0, 36));
        field.setBorder(BorderFactory.createEmptyBorder(8, 12, 8, 12));
        field.setOpaque(false);
        return field;
    }

    private JButton createStyledButton(String text, int width, int height) {
        JButton button = new JButton(text) {
            @Override
            protected void paintComponent(Graphics g) {
                Graphics2D g2 = (Graphics2D) g.create();
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

                ThemeManager.Colors colors = ThemeManager.getCurrentColors();

                if (getModel().isPressed()) {
                    g2.setColor(colors.primary.darker());
                } else if (getModel().isRollover() && isEnabled()) {
                    g2.setColor(colors.buttonHover);
                } else if (isEnabled()) {
                    g2.setColor(colors.primary);
                } else {
                    g2.setColor(colors.progressBackground);
                }

                g2.fillRoundRect(0, 0, getWidth(), getHeight(), 12, 12);

                g2.setColor(isEnabled() ? Color.WHITE : Color.GRAY);
                g2.setFont(getFont());
                FontMetrics fm = g2.getFontMetrics();
                int textX = (getWidth() - fm.stringWidth(getText())) / 2;
                int textY = (getHeight() + fm.getAscent()) / 2 - 2;
                g2.drawString(getText(), textX, textY);

                g2.dispose();
            }
        };

        button.setFont(ThemeManager.getBoldFont());
        button.setPreferredSize(new Dimension(width, height));
        button.setFocusPainted(false);
        button.setBorderPainted(false);
        button.setContentAreaFilled(false);
        button.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));

        return button;
    }

    private JProgressBar createStyledProgressBar() {
        JProgressBar bar = new JProgressBar(0, 100) {
            @Override
            protected void paintComponent(Graphics g) {
                Graphics2D g2 = (Graphics2D) g.create();
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

                int width = getWidth();
                int height = getHeight();
                int progressWidth = (int) ((double) getValue() / getMaximum() * width);

                ThemeManager.Colors colors = ThemeManager.getCurrentColors();

                g2.setColor(colors.progressBackground);
                g2.fillRoundRect(0, 0, width, height, 10, 10);

                if (progressWidth > 0) {
                    g2.setColor(colors.primary);
                    g2.fillRoundRect(0, 0, progressWidth, height, 10, 10);

                    g2.setColor(new Color(255, 255, 255, 30));
                    g2.fillRoundRect(0, 0, progressWidth, height / 2, 10, 10);
                }

                g2.dispose();
            }
        };

        bar.setPreferredSize(new Dimension(450, 24));
        bar.setMinimumSize(new Dimension(450, 24));
        bar.setMaximumSize(new Dimension(450, 24));
        bar.setBorderPainted(false);
        bar.setOpaque(false);

        return bar;
    }

    private void setupEventHandlers() {
        modpackCombo.addActionListener(e -> {
            String selected = (String) modpackCombo.getSelectedItem();
            if (selected != null) {
                installer.selectedInstall = selected;
            }
        });

        refreshButton.addActionListener(e -> refreshModpacks());
        browseButton.addActionListener(e -> browseDirectory());
        installButton.addActionListener(e -> performInstall());
        backButton.addActionListener(e -> showMainPanel());
    }

    private void refreshModpacks() {
        refreshButton.setEnabled(false);
        refreshButton.setText("Actualizando...");

        SwingWorker<InstallerConfig, Void> worker = new SwingWorker<>() {
            @Override
            protected InstallerConfig doInBackground() {
                try {
                    JsonObject configObject = RemoteConfigLoader.loadAndValidateRemoteConfig();
                    if (configObject != null) {
                        return ConfigParser.parse(configObject);
                    }
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

            }
        };
        worker.execute();
    }

    private void browseDirectory() {
        JFileChooser chooser = new JFileChooser();
        chooser.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
        chooser.setCurrentDirectory(installer.gameDir.toFile());
        chooser.setDialogTitle("Seleccionar directorio de instalación");

        if (chooser.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) {
            installer.gameDir = chooser.getSelectedFile().toPath();
            pathField.setText(installer.gameDir.toString());
        }
    }

    private void performInstall() {
        showProgressPanel();
        resetProgress();

        ProgressCallback callback = new ProgressCallback() {
            @Override
            public void onPhaseStart(String phaseName, long totalItems) {
                SwingUtilities.invokeLater(() -> {
                    statusLabel.setText("Iniciando " + phaseName.toLowerCase() + "...");
                    progressBar.setValue(0);
                    progressLabel.setText("0% - Iniciando " + phaseName.toLowerCase() + "...");
                });
            }

            @Override
            public void onProgress(long itemsCompleted, long totalItems, double speed, String currentItem, String phaseName) {
                final int percentage = totalItems > 0 ? (int) ((itemsCompleted * 100) / totalItems) : 0;

                SwingUtilities.invokeLater(() -> {
                    progressBar.setValue(Math.min(100, percentage));

                    if ("Descarga".equals(phaseName)) {
                        String speedText = formatSpeed(speed);
                        String sizeText = formatBytes(itemsCompleted);
                        String totalText = totalItems > 0 ? formatBytes(totalItems) : "Desconocido";
                        progressLabel.setText(String.format("%d%% - %s (%s/%s)", percentage, speedText, sizeText, totalText));
                        statusLabel.setText("Descargando: " + currentItem);
                    } else if ("Extracción".equals(phaseName)) {
                        String filesPerSec = String.format("%.1f archivos/s", speed);
                        progressLabel.setText(String.format("%d%% - %s (%d/%d archivos)", percentage, filesPerSec, itemsCompleted, totalItems));
                        statusLabel.setText("Extrayendo: " + currentItem);
                    }
                });
            }

            @Override
            public void onPhaseComplete(String phaseName) {
                SwingUtilities.invokeLater(() -> {
                    statusLabel.setText(phaseName + " completada");
                });
            }

            @Override
            public void onAllComplete() {
                SwingUtilities.invokeLater(() -> {
                    statusLabel.setText("¡Instalación completada!");
                    progressBar.setValue(100);
                    progressLabel.setText("100% - ¡Completado!");
                });
            }
        };

        SwingWorker<Boolean, Void> worker = new SwingWorker<>() {
            private String errorMessage = null;

            @Override
            protected Boolean doInBackground() {
                try {
                    SwingUtilities.invokeLater(() -> {
                        statusLabel.setText("Preparando instalación...");
                        progressBar.setValue(0);
                        progressLabel.setText("0% - Preparando...");
                    });

                    installer.install(callback);

                    SwingUtilities.invokeLater(() -> {
                        statusLabel.setText("Procesando archivos descargados...");
                        progressBar.setValue(95);
                        progressLabel.setText("95% - Finalizando...");
                    });

                    Thread.sleep(500);

                    SwingUtilities.invokeLater(() -> {
                        statusLabel.setText("¡Instalación completada!");
                        progressBar.setValue(100);
                        progressLabel.setText("100% - ¡Completado!");
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
                        showResult("¡La instalación de " + installer.selectedInstall + " fue completada exitosamente!",
                                ThemeManager.getCurrentColors().success);
                    } else {
                        showResult(errorMessage != null ? errorMessage : "Error desconocido durante la instalación", Color.RED);
                    }
                } catch (Exception ex) {
                    showResult("Error inesperado: " + ex.getMessage(), Color.RED);
                }
            }
        };
        worker.execute();
    }

    private String buildDetailedErrorMessage(Exception e) {
        StringBuilder message = new StringBuilder();
        message.append("Error durante la instalación:\n\n");

        if (e.getMessage().contains("Errores durante la descarga")) {
            message.append("No se pudo descargar el modpack.\n\n");
            message.append("Posibles causas:\n");
            message.append("• Conexión a internet inestable\n");
            message.append("• Firewall/Antivirus bloqueando la descarga\n");
            message.append("• Servidor temporalmente no disponible\n");
            message.append("• Sin permisos de escritura en el directorio\n\n");
            message.append("Soluciones:\n");
            message.append("1. Verifica tu conexión a internet\n");
            message.append("2. Desactiva temporalmente el antivirus\n");
            message.append("3. Ejecuta el instalador como administrador\n");
            message.append("4. Intenta de nuevo en unos minutos");
        } else {
            message.append(e.getMessage());
        }

        return message.toString();
    }

    private String formatBytes(long bytes) {
        if (bytes < 1024) return bytes + " B";
        int exp = (int) (Math.log(bytes) / Math.log(1024));
        String pre = "KMGTPE".charAt(exp - 1) + "";
        return String.format("%.1f %sB", bytes / Math.pow(1024, exp), pre);
    }

    private String formatSpeed(double bytesPerSecond) {
        return formatBytes((long) bytesPerSecond) + "/s";
    }

    private void resetProgress() {
        progressBar.setValue(0);
        progressLabel.setText("0%");
        statusLabel.setText("Preparando instalación...");
    }

    private void showMainPanel() {
        currentState = PanelState.MAIN;
        CardLayout layout = (CardLayout) getLayout();
        layout.show(this, "MAIN");
    }

    private void showProgressPanel() {
        currentState = PanelState.PROGRESS;
        CardLayout layout = (CardLayout) getLayout();
        layout.show(this, "PROGRESS");
    }

    private void showResult(String message, Color color) {
        currentState = PanelState.RESULT;
        resultLabel.setText("<html><center>" + message.replace("\n", "<br>") + "</center></html>");
        resultLabel.setForeground(color);

        CardLayout layout = (CardLayout) getLayout();
        layout.show(this, "RESULT");
    }

    public void updateTheme() {
        ThemeManager.Colors colors = ThemeManager.getCurrentColors();

        for (Component panel : new Component[]{mainPanel, progressPanel, resultPanel}) {
            updatePanelTheme(panel, colors);
        }

        if (modpackCombo != null) {
            modpackCombo.setBackground(colors.fieldBackground);
            modpackCombo.setForeground(colors.text);
        }

        if (pathField != null) {
            pathField.setBackground(colors.fieldBackground);
            pathField.setForeground(colors.text);
            pathField.setCaretColor(colors.text);
        }

        if (statusLabel != null) statusLabel.setForeground(colors.text);
        if (progressLabel != null) progressLabel.setForeground(new Color(180, 180, 180));

        repaint();
    }

    private void updatePanelTheme(Component comp, ThemeManager.Colors colors) {
        if (comp instanceof JLabel) {
            comp.setForeground(colors.text);
        } else if (comp instanceof Container) {
            for (Component child : ((Container) comp).getComponents()) {
                updatePanelTheme(child, colors);
            }
        }
    }
}