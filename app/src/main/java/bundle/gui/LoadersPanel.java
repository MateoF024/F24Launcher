package bundle.gui;

import bundle.installer.BundleInstaller;
import bundle.loader.LoaderManager;
import bundle.util.MinecraftDirectoryValidator;
import bundle.util.OperatingSystem;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class LoadersPanel extends JPanel {
    private final BundleInstaller installer;
    private LoaderManager loaderManager;
    private Path loaderGameDir;

    private enum PanelState { MAIN, PROGRESS, RESULT }
    private PanelState currentState = PanelState.MAIN;

    private JPanel mainPanel;

    // Componentes compartidos via GuiFactory
    private GuiFactory.ProgressPanelComponents progressComponents;
    private GuiFactory.ResultPanelComponents resultComponents;

    private JButton[] loaderButtons;
    private String selectedLoader = "Fabric";
    private JComboBox<String> versionCombo;
    private JComboBox<LoaderManager.LoaderVersion> loaderVersionCombo;
    private JTextField loaderPathField;
    private JButton loaderBrowseButton;
    private JButton installLoaderButton;

    private List<LoaderManager.MinecraftVersion> gameVersions = new ArrayList<>();

    // #fix doble carga — flag para evitar disparar updateLoaderVersions durante la carga inicial
    private boolean versionsLoading = false;

    public LoadersPanel(BundleInstaller installer) {
        this.installer = installer;
        this.loaderGameDir = OperatingSystem.getCurrent().getMCDir();
        this.loaderManager = new LoaderManager(loaderGameDir);

        setLayout(new CardLayout());
        setOpaque(false);

        createComponents();
        setupEventHandlers();
        updateTheme();

        loadGameVersions();
        showMainPanel();
    }

    private void createComponents() {
        createMainPanel();

        progressComponents = GuiFactory.createProgressPanel();
        resultComponents   = GuiFactory.createResultPanel(this::showMainPanel);

        add(mainPanel,                "MAIN");
        add(progressComponents.panel, "PROGRESS");
        add(resultComponents.panel,   "RESULT");
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

        // Loader buttons
        gbc.gridx = 0; gbc.gridy = 0;
        JLabel loaderLabel = new JLabel("Loader:");
        loaderLabel.setFont(ThemeManager.getBoldFont());
        center.add(loaderLabel, gbc);

        gbc.gridx = 1; gbc.gridwidth = 2; gbc.fill = GridBagConstraints.HORIZONTAL;
        center.add(createLoaderButtonsPanel(), gbc);

        // Versión Minecraft
        gbc.gridx = 0; gbc.gridy = 1; gbc.gridwidth = 1; gbc.fill = GridBagConstraints.NONE;
        JLabel versionLabel = new JLabel("Versión de Minecraft:");
        versionLabel.setFont(ThemeManager.getBoldFont());
        center.add(versionLabel, gbc);

        gbc.gridx = 1; gbc.gridwidth = 2; gbc.fill = GridBagConstraints.HORIZONTAL; gbc.weightx = 1.0;
        versionCombo = GuiFactory.createStyledComboBox(new String[]{"Cargando..."});
        center.add(versionCombo, gbc);

        // Versión loader
        gbc.gridx = 0; gbc.gridy = 2; gbc.gridwidth = 1; gbc.weightx = 0; gbc.fill = GridBagConstraints.NONE;
        JLabel loaderVersionLabel = new JLabel("Versión del loader:");
        loaderVersionLabel.setFont(ThemeManager.getBoldFont());
        center.add(loaderVersionLabel, gbc);

        gbc.gridx = 1; gbc.gridwidth = 2; gbc.fill = GridBagConstraints.HORIZONTAL; gbc.weightx = 1.0;
        loaderVersionCombo = GuiFactory.createStyledGenericComboBox(new LoaderManager.LoaderVersion[]{});
        center.add(loaderVersionCombo, gbc);

        // Directorio
        gbc.gridx = 0; gbc.gridy = 3; gbc.gridwidth = 1; gbc.weightx = 0; gbc.fill = GridBagConstraints.NONE;
        JLabel pathLabel = new JLabel("Directorio:");
        pathLabel.setFont(ThemeManager.getBoldFont());
        center.add(pathLabel, gbc);

        gbc.gridx = 1; gbc.weightx = 1.0; gbc.fill = GridBagConstraints.HORIZONTAL;
        loaderPathField = GuiFactory.createStyledTextField(loaderGameDir.toString());
        center.add(loaderPathField, gbc);

        gbc.gridx = 2; gbc.weightx = 0; gbc.fill = GridBagConstraints.NONE;
        loaderBrowseButton = GuiFactory.createStyledButton("Buscar", 100, 36);
        center.add(loaderBrowseButton, gbc);

        // Instalar
        gbc.gridx = 0; gbc.gridy = 4; gbc.gridwidth = 3;
        gbc.anchor = GridBagConstraints.CENTER;
        gbc.insets = new Insets(20, 8, 8, 8);
        installLoaderButton = GuiFactory.createStyledButton("Instalar", 160, 45);
        center.add(installLoaderButton, gbc);

        GridBagConstraints mainGbc = new GridBagConstraints();
        mainGbc.gridx = 0; mainGbc.gridy = 0;
        mainGbc.weightx = 1.0; mainGbc.weighty = 1.0;
        mainGbc.anchor = GridBagConstraints.CENTER;
        mainPanel.add(center, mainGbc);
    }

    private JPanel createLoaderButtonsPanel() {
        JPanel panel = new JPanel(new FlowLayout(FlowLayout.LEFT, 10, 0));
        panel.setOpaque(false);

        String[] loaders = {"Fabric", "Forge", "NeoForge"};
        loaderButtons = new JButton[loaders.length];

        for (int i = 0; i < loaders.length; i++) {
            final String loaderName = loaders[i];
            loaderButtons[i] = createLoaderButton(loaderName);
            panel.add(loaderButtons[i]);
        }
        return panel;
    }

    private JButton createLoaderButton(String loaderName) {
        JButton button = new JButton(loaderName) {
            @Override
            protected void paintComponent(Graphics g) {
                Graphics2D g2 = (Graphics2D) g.create();
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                ThemeManager.Colors colors = ThemeManager.getCurrentColors();
                boolean isSelected = loaderName.equals(selectedLoader);
                if (isSelected) {
                    g2.setColor(colors.primary);
                } else if (getModel().isRollover()) {
                    g2.setColor(colors.buttonHover.darker());
                } else {
                    g2.setColor(colors.fieldBackground);
                }
                g2.fillRoundRect(0, 0, getWidth(), getHeight(), 8, 8);
                if (isSelected) {
                    g2.setColor(colors.primary.brighter());
                    g2.setStroke(new BasicStroke(2));
                    g2.drawRoundRect(0, 0, getWidth() - 1, getHeight() - 1, 8, 8);
                }
                g2.setColor(isSelected ? Color.WHITE : colors.text);
                g2.setFont(getFont());
                FontMetrics fm = g2.getFontMetrics();
                int textX = (getWidth() - fm.stringWidth(getText())) / 2;
                int textY = (getHeight() + fm.getAscent()) / 2 - 2;
                g2.drawString(getText(), textX, textY);
                g2.dispose();
            }
        };
        button.setFont(ThemeManager.getBoldFont());
        button.setPreferredSize(new Dimension(85, 36));
        button.setFocusPainted(false);
        button.setBorderPainted(false);
        button.setContentAreaFilled(false);
        button.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        button.addActionListener(e -> {
            selectedLoader = loaderName;
            updateLoaderVersions();
            repaintLoaderButtons();
        });
        return button;
    }

    private void repaintLoaderButtons() {
        for (JButton b : loaderButtons) b.repaint();
    }

    private void setupEventHandlers() {
        // #fix doble carga — solo disparar si no estamos en carga inicial
        versionCombo.addActionListener(e -> {
            if (!versionsLoading) updateLoaderVersions();
        });
        loaderBrowseButton.addActionListener(e -> browseLoaderDirectory());
        installLoaderButton.addActionListener(e -> performLoaderInstall());
    }

    private void loadGameVersions() {
        versionsLoading = true; // #fix doble carga

        SwingWorker<List<LoaderManager.MinecraftVersion>, Void> worker = new SwingWorker<>() {
            @Override
            protected List<LoaderManager.MinecraftVersion> doInBackground() {
                try {
                    return loaderManager.getMinecraftVersions().get();
                } catch (Exception e) {
                    System.err.println("[LOADER PANEL] Error cargando versiones de Minecraft: " + e.getMessage());
                    return Collections.emptyList();
                }
            }

            @Override
            protected void done() {
                try {
                    gameVersions = get();
                    if (!gameVersions.isEmpty()) {
                        List<String> releases = new ArrayList<>();
                        for (LoaderManager.MinecraftVersion v : gameVersions) {
                            if (v.stable) releases.add(v.id);
                        }
                        DefaultComboBoxModel<String> model = new DefaultComboBoxModel<>(
                                releases.toArray(new String[0]));
                        versionCombo.setModel(model);

                        if (!releases.isEmpty()) {
                            versionCombo.setSelectedIndex(0);
                        }
                    } else {
                        versionCombo.setModel(new DefaultComboBoxModel<>(new String[]{"Error: No hay conexión"}));
                    }
                } catch (Exception e) {
                    versionCombo.setModel(new DefaultComboBoxModel<>(new String[]{"Error: No hay conexión"}));
                } finally {
                    versionsLoading = false; // #fix doble carga — liberar flag antes de cargar versiones loader
                    updateLoaderVersions();  // una única llamada controlada
                }
            }
        };
        worker.execute();
    }

    private void updateLoaderVersions() {
        String selectedVersion = (String) versionCombo.getSelectedItem();
        if (selectedVersion == null
                || "Cargando...".equals(selectedVersion)
                || selectedVersion.startsWith("Error:")) return;

        LoaderManager.LoaderType loaderType = switch (selectedLoader) {
            case "Fabric"   -> LoaderManager.LoaderType.FABRIC;
            case "Forge"    -> LoaderManager.LoaderType.FORGE;
            case "NeoForge" -> LoaderManager.LoaderType.NEOFORGE;
            default -> null;
        };
        if (loaderType == null) return;

        loaderVersionCombo.setModel(new DefaultComboBoxModel<>(new LoaderManager.LoaderVersion[]{
                new LoaderManager.LoaderVersion("Cargando...", selectedVersion, loaderType, true, "")
        }));

        SwingWorker<List<LoaderManager.LoaderVersion>, Void> worker = new SwingWorker<>() {
            @Override
            protected List<LoaderManager.LoaderVersion> doInBackground() {
                try {
                    return loaderManager.getLoaderVersions(loaderType, selectedVersion).get();
                } catch (Exception e) {
                    System.err.println("[LOADER PANEL] Error cargando versiones de " + selectedLoader + ": " + e.getMessage());
                    return Collections.emptyList();
                }
            }

            @Override
            protected void done() {
                try {
                    List<LoaderManager.LoaderVersion> versions = get();
                    if (!versions.isEmpty()) {
                        loaderVersionCombo.setModel(new DefaultComboBoxModel<>(
                                versions.toArray(new LoaderManager.LoaderVersion[0])));
                        loaderVersionCombo.setSelectedIndex(0);
                    } else {
                        loaderVersionCombo.setModel(new DefaultComboBoxModel<>(new LoaderManager.LoaderVersion[]{
                                new LoaderManager.LoaderVersion(
                                        "No hay versiones disponibles", selectedVersion, loaderType, false, "")
                        }));
                    }
                } catch (Exception e) {
                    loaderVersionCombo.setModel(new DefaultComboBoxModel<>(new LoaderManager.LoaderVersion[]{
                            new LoaderManager.LoaderVersion(
                                    "Error de conexión", selectedVersion, loaderType, false, "")
                    }));
                }
            }
        };
        worker.execute();
    }

    private void browseLoaderDirectory() {
        JFileChooser chooser = new JFileChooser();
        chooser.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
        chooser.setCurrentDirectory(loaderGameDir.toFile());
        chooser.setDialogTitle("Seleccionar directorio .minecraft para instalación de loader");

        if (chooser.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) {
            loaderGameDir = chooser.getSelectedFile().toPath();
            loaderPathField.setText(loaderGameDir.toString());
            loaderManager = new LoaderManager(loaderGameDir);
        }
    }

    private void performLoaderInstall() {
        MinecraftDirectoryValidator.ValidationResult validation =
                MinecraftDirectoryValidator.validateMinecraftDirectory(loaderGameDir);

        if (!validation.isValid) {
            JOptionPane.showMessageDialog(this, validation.errorMessage,
                    "Directorio no válido", JOptionPane.ERROR_MESSAGE);
            return;
        }

        LoaderManager.LoaderVersion selectedLoaderVersion =
                (LoaderManager.LoaderVersion) loaderVersionCombo.getSelectedItem();
        if (selectedLoaderVersion == null || selectedLoaderVersion.downloadUrl.isEmpty()) {
            JOptionPane.showMessageDialog(this, "Selecciona una versión del loader válida",
                    "Selección requerida", JOptionPane.WARNING_MESSAGE);
            return;
        }

        showProgressPanel();
        progressComponents.reset();

        LoaderManager.InstallProgressCallback callback = new LoaderManager.InstallProgressCallback() {
            @Override
            public void onStart(String message) {
                SwingUtilities.invokeLater(() -> {
                    progressComponents.statusLabel.setText(message);
                    progressComponents.progressBar.setValue(0);
                    progressComponents.progressLabel.setText("0% - " + message);
                });
            }

            @Override
            public void onProgress(int percentage, String message) {
                SwingUtilities.invokeLater(() -> {
                    progressComponents.progressBar.setValue(percentage);
                    progressComponents.progressLabel.setText(percentage + "% - " + message);
                    progressComponents.statusLabel.setText(message);
                });
            }

            @Override
            public void onComplete(boolean success, String message) {
                SwingUtilities.invokeLater(() -> {
                    if (success) {
                        showResult("¡" + selectedLoader + " " + selectedLoaderVersion.version +
                                        " instalado correctamente para Minecraft " +
                                        selectedLoaderVersion.minecraftVersion + "!",
                                ThemeManager.getCurrentColors().success);
                    } else {
                        showResult("Error: " + message, Color.RED);
                    }
                });
            }
        };

        SwingWorker<Boolean, Void> worker = new SwingWorker<>() {
            @Override
            protected Boolean doInBackground() {
                try {
                    LoaderManager.InstallResult result =
                            loaderManager.installLoader(selectedLoaderVersion, callback).get();
                    return result.success;
                } catch (Exception e) {
                    System.err.println("Error durante instalación de loader: " + e.getMessage());
                    SwingUtilities.invokeLater(() ->
                            showResult("Error inesperado: " + e.getMessage(), Color.RED));
                    return false;
                }
            }

            @Override
            protected void done() { }
        };
        worker.execute();
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
        if (progressComponents != null) updatePanelTheme(progressComponents.panel, colors);
        if (resultComponents != null)   updatePanelTheme(resultComponents.panel, colors);

        if (versionCombo != null) {
            versionCombo.setBackground(colors.fieldBackground);
            versionCombo.setForeground(colors.text);
        }
        if (loaderVersionCombo != null) {
            loaderVersionCombo.setBackground(colors.fieldBackground);
            loaderVersionCombo.setForeground(colors.text);
        }
        if (loaderPathField != null) {
            loaderPathField.setBackground(colors.fieldBackground);
            loaderPathField.setForeground(colors.text);
            loaderPathField.setCaretColor(colors.text);
        }
        if (loaderButtons != null) repaintLoaderButtons();
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