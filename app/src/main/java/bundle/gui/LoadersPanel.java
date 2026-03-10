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

    private enum PanelState {
        MAIN, PROGRESS, RESULT
    }
    private PanelState currentState = PanelState.MAIN;

    private JPanel mainPanel;
    private JPanel progressPanel;
    private JPanel resultPanel;

    private JButton[] loaderButtons;
    private String selectedLoader = "Fabric";
    private JComboBox<String> versionCombo;
    private JComboBox<LoaderManager.LoaderVersion> loaderVersionCombo;
    private JTextField loaderPathField;
    private JButton loaderBrowseButton;
    private JButton installLoaderButton;

    private JProgressBar progressBar;
    private JLabel statusLabel;
    private JLabel progressLabel;

    private JLabel resultLabel;
    private JButton backButton;

    private List<LoaderManager.MinecraftVersion> gameVersions = new ArrayList<>();

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
        JLabel loaderLabel = new JLabel("Loader:");
        loaderLabel.setFont(ThemeManager.getBoldFont());
        centerContainer.add(loaderLabel, gbc);

        gbc.gridx = 1; gbc.gridwidth = 2; gbc.fill = GridBagConstraints.HORIZONTAL;
        JPanel loaderButtonsPanel = createLoaderButtonsPanel();
        centerContainer.add(loaderButtonsPanel, gbc);

        gbc.gridx = 0; gbc.gridy = 1; gbc.gridwidth = 1; gbc.fill = GridBagConstraints.NONE;
        JLabel versionLabel = new JLabel("Versión de Minecraft:");
        versionLabel.setFont(ThemeManager.getBoldFont());
        centerContainer.add(versionLabel, gbc);

        gbc.gridx = 1; gbc.gridwidth = 2; gbc.fill = GridBagConstraints.HORIZONTAL; gbc.weightx = 1.0;
        versionCombo = createStyledComboBox(new String[]{"Cargando..."});
        centerContainer.add(versionCombo, gbc);

        gbc.gridx = 0; gbc.gridy = 2; gbc.gridwidth = 1; gbc.weightx = 0; gbc.fill = GridBagConstraints.NONE;
        JLabel loaderVersionLabel = new JLabel("Versión del loader:");
        loaderVersionLabel.setFont(ThemeManager.getBoldFont());
        centerContainer.add(loaderVersionLabel, gbc);

        gbc.gridx = 1; gbc.gridwidth = 2; gbc.fill = GridBagConstraints.HORIZONTAL; gbc.weightx = 1.0;
        loaderVersionCombo = createStyledLoaderComboBox(new LoaderManager.LoaderVersion[]{});
        centerContainer.add(loaderVersionCombo, gbc);

        gbc.gridx = 0; gbc.gridy = 3; gbc.gridwidth = 1; gbc.weightx = 0; gbc.fill = GridBagConstraints.NONE;
        JLabel pathLabel = new JLabel("Directorio:");
        pathLabel.setFont(ThemeManager.getBoldFont());
        centerContainer.add(pathLabel, gbc);

        gbc.gridx = 1; gbc.weightx = 1.0; gbc.fill = GridBagConstraints.HORIZONTAL;
        loaderPathField = createStyledTextField(loaderGameDir.toString());
        centerContainer.add(loaderPathField, gbc);

        gbc.gridx = 2; gbc.weightx = 0; gbc.fill = GridBagConstraints.NONE;
        loaderBrowseButton = createStyledButton("Buscar", 100, 36);
        centerContainer.add(loaderBrowseButton, gbc);

        gbc.gridx = 0; gbc.gridy = 4; gbc.gridwidth = 3;
        gbc.anchor = GridBagConstraints.CENTER;
        gbc.insets = new Insets(20, 8, 8, 8);
        installLoaderButton = createStyledButton("Instalar", 160, 45);
        centerContainer.add(installLoaderButton, gbc);

        GridBagConstraints mainGbc = new GridBagConstraints();
        mainGbc.gridx = 0; mainGbc.gridy = 0;
        mainGbc.weightx = 1.0; mainGbc.weighty = 1.0;
        mainGbc.anchor = GridBagConstraints.CENTER;
        mainPanel.add(centerContainer, mainGbc);

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
        for (JButton button : loaderButtons) {
            button.repaint();
        }
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

    private JComboBox<LoaderManager.LoaderVersion> createStyledLoaderComboBox(LoaderManager.LoaderVersion[] items) {
        JComboBox<LoaderManager.LoaderVersion> combo = new JComboBox<>(items);
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
        versionCombo.addActionListener(e -> updateLoaderVersions());
        loaderBrowseButton.addActionListener(e -> browseLoaderDirectory());
        installLoaderButton.addActionListener(e -> performLoaderInstall());
        backButton.addActionListener(e -> showMainPanel());
    }

    private void loadGameVersions() {
        SwingWorker<List<LoaderManager.MinecraftVersion>, Void> worker = new SwingWorker<>() {
            @Override
            protected List<LoaderManager.MinecraftVersion> doInBackground() {
                try {
                    System.out.println("[LOADER PANEL] Cargando versiones de Minecraft desde Mojang...");
                    return loaderManager.getMinecraftVersions().get();
                } catch (Exception e) {
                    System.err.println("[LOADER PANEL] Error cargando versiones de Minecraft: " + e.getMessage());
                    e.printStackTrace();
                    return Collections.emptyList();
                }
            }

            @Override
            protected void done() {
                try {
                    gameVersions = get();
                    if (!gameVersions.isEmpty()) {
                        List<String> releaseVersions = new ArrayList<>();
                        for (LoaderManager.MinecraftVersion version : gameVersions) {
                            if (version.stable) {
                                releaseVersions.add(version.id);
                            }
                        }

                        System.out.println("[LOADER PANEL] Versiones de MC cargadas: " + releaseVersions.size());

                        DefaultComboBoxModel<String> model = new DefaultComboBoxModel<>(
                                releaseVersions.toArray(new String[0]));
                        versionCombo.setModel(model);

                        if (!releaseVersions.isEmpty()) {
                            versionCombo.setSelectedIndex(0);
                            updateLoaderVersions();
                        }
                    } else {
                        System.err.println("[LOADER PANEL] No se pudieron cargar versiones de Minecraft");
                        versionCombo.setModel(new DefaultComboBoxModel<>(new String[]{"Error: No hay conexión"}));
                    }
                } catch (Exception e) {
                    System.err.println("[LOADER PANEL] Error actualizando combo de versiones: " + e.getMessage());
                    e.printStackTrace();
                    versionCombo.setModel(new DefaultComboBoxModel<>(new String[]{"Error: No hay conexión"}));
                }
            }
        };
        worker.execute();
    }

    private void updateLoaderVersions() {
        String selectedVersion = (String) versionCombo.getSelectedItem();
        if (selectedVersion == null || "Cargando...".equals(selectedVersion) || selectedVersion.startsWith("Error:")) {
            return;
        }

        LoaderManager.LoaderType loaderType;
        switch (selectedLoader) {
            case "Fabric":
                loaderType = LoaderManager.LoaderType.FABRIC;
                break;
            case "Forge":
                loaderType = LoaderManager.LoaderType.FORGE;
                break;
            case "NeoForge":
                loaderType = LoaderManager.LoaderType.NEOFORGE;
                break;
            default:
                return;
        }

        LoaderManager.LoaderVersion loadingVersion = new LoaderManager.LoaderVersion(
                "Cargando...", selectedVersion, loaderType, true, "");
        loaderVersionCombo.setModel(new DefaultComboBoxModel<>(new LoaderManager.LoaderVersion[]{loadingVersion}));

        SwingWorker<List<LoaderManager.LoaderVersion>, Void> worker = new SwingWorker<>() {
            @Override
            protected List<LoaderManager.LoaderVersion> doInBackground() {
                try {
                    System.out.println("[LOADER PANEL] Cargando versiones de " + selectedLoader + " para MC " + selectedVersion + "...");
                    return loaderManager.getLoaderVersions(loaderType, selectedVersion).get();
                } catch (Exception e) {
                    System.err.println("[LOADER PANEL] Error cargando versiones de " + selectedLoader + ": " + e.getMessage());
                    e.printStackTrace();
                    return Collections.emptyList();
                }
            }

            @Override
            protected void done() {
                try {
                    List<LoaderManager.LoaderVersion> versions = get();
                    if (!versions.isEmpty()) {
                        System.out.println("[LOADER PANEL] Cargadas " + versions.size() + " versiones de " + selectedLoader);
                        DefaultComboBoxModel<LoaderManager.LoaderVersion> model = new DefaultComboBoxModel<>(
                                versions.toArray(new LoaderManager.LoaderVersion[0]));
                        loaderVersionCombo.setModel(model);
                        loaderVersionCombo.setSelectedIndex(0);
                    } else {
                        System.out.println("[LOADER PANEL] No se encontraron versiones de " + selectedLoader + " para MC " + selectedVersion);
                        LoaderManager.LoaderVersion noVersion = new LoaderManager.LoaderVersion(
                                "No hay versiones disponibles", selectedVersion, loaderType, false, "");
                        loaderVersionCombo.setModel(new DefaultComboBoxModel<>(new LoaderManager.LoaderVersion[]{noVersion}));
                    }
                } catch (Exception e) {
                    System.err.println("[LOADER PANEL] Error actualizando versiones de loader: " + e.getMessage());
                    e.printStackTrace();
                    LoaderManager.LoaderVersion errorVersion = new LoaderManager.LoaderVersion(
                            "Error de conexión", selectedVersion, loaderType, false, "");
                    loaderVersionCombo.setModel(new DefaultComboBoxModel<>(new LoaderManager.LoaderVersion[]{errorVersion}));
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

        LoaderManager.LoaderVersion selectedLoaderVersion = (LoaderManager.LoaderVersion) loaderVersionCombo.getSelectedItem();
        if (selectedLoaderVersion == null || selectedLoaderVersion.downloadUrl.isEmpty()) {
            JOptionPane.showMessageDialog(this, "Selecciona una versión del loader válida",
                    "Selección requerida", JOptionPane.WARNING_MESSAGE);
            return;
        }

        showProgressPanel();
        resetProgress();

        LoaderManager.InstallProgressCallback callback = new LoaderManager.InstallProgressCallback() {
            @Override
            public void onStart(String message) {
                SwingUtilities.invokeLater(() -> {
                    statusLabel.setText(message);
                    progressBar.setValue(0);
                    progressLabel.setText("0% - " + message);
                });
            }

            @Override
            public void onProgress(int percentage, String message) {
                SwingUtilities.invokeLater(() -> {
                    progressBar.setValue(percentage);
                    progressLabel.setText(percentage + "% - " + message);
                    statusLabel.setText(message);
                });
            }

            @Override
            public void onComplete(boolean success, String message) {
                SwingUtilities.invokeLater(() -> {
                    if (success) {
                        showResult("¡" + selectedLoader + " " + selectedLoaderVersion.version +
                                        " instalado correctamente para Minecraft " + selectedLoaderVersion.minecraftVersion + "!",
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
                    LoaderManager.InstallResult result = loaderManager.installLoader(selectedLoaderVersion, callback).get();
                    return result.success;
                } catch (Exception e) {
                    System.err.println("Error during loader installation: " + e.getMessage());
                    e.printStackTrace();
                    SwingUtilities.invokeLater(() -> {
                        showResult("Error inesperado: " + e.getMessage(), Color.RED);
                    });
                    return false;
                }
            }

            @Override
            protected void done() {
            }
        };
        worker.execute();
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

        String htmlMessage = message.replace("\n", "<br>")
                .replace(". ", ".<br>")
                .replace("NeoForge", "NeoForge");

        resultLabel.setText("<html><div style='text-align: center; width: 400px;'>" + htmlMessage + "</div></html>");
        resultLabel.setForeground(color);

        CardLayout layout = (CardLayout) getLayout();
        layout.show(this, "RESULT");
    }

    public void updateTheme() {
        ThemeManager.Colors colors = ThemeManager.getCurrentColors();

        for (Component panel : new Component[]{mainPanel, progressPanel, resultPanel}) {
            updatePanelTheme(panel, colors);
        }

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

        if (loaderButtons != null) {
            repaintLoaderButtons();
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