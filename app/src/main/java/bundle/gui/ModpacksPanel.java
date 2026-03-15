package bundle.gui;

import bundle.installer.BundleInstaller;
import bundle.config.RemoteConfigLoader;
import bundle.config.ConfigParser;
import bundle.config.DownloadConfig;
import bundle.config.InstallerConfig;
import bundle.download.DownloadException;
import bundle.download.ProgressCallback;
import bundle.settings.AppSettings;
import com.google.gson.Gson;
import com.google.gson.JsonObject;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class ModpacksPanel extends JPanel {

    private final BundleInstaller installer;

    private enum PanelState { MAIN, PROGRESS, RESULT }

    private JPanel mainPanel;
    private GuiFactory.ProgressPanelComponents progressComponents;
    private GuiFactory.ResultPanelComponents resultComponents;

    private DefaultListModel<String> listModel;
    private JList<String> modpackList;
    private JButton refreshButton;

    private JLabel iconLabel;
    private JLabel nameLabel;
    private JLabel versionLoaderLabel;
    private JTextArea descriptionArea;
    private JTextField pathField;
    private JButton browseButton;
    private JButton installButton;
    private JPanel detailContainer;
    private CardLayout detailCardLayout;

    private final Map<String, ImageIcon> iconCache = new HashMap<>();
    private final Set<String> loadingIcons = Collections.synchronizedSet(new HashSet<>());

    private static final ExecutorService ICON_EXECUTOR = Executors.newFixedThreadPool(4);
    private static final okhttp3.OkHttpClient ICON_CLIENT = new okhttp3.OkHttpClient.Builder()
            .connectTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
            .readTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .build();

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
        buildMainPanel();
        progressComponents = GuiFactory.createProgressPanel();
        resultComponents   = GuiFactory.createResultPanel(this::showMainPanel);
        add(mainPanel,             "MAIN");
        add(progressComponents.panel, "PROGRESS");
        add(resultComponents.panel,   "RESULT");
    }

    private void buildMainPanel() {
        mainPanel = new JPanel(new BorderLayout());
        mainPanel.setOpaque(false);

        JSplitPane split = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, buildLeftPanel(), buildRightPanel());
        split.setDividerSize(3);
        split.setResizeWeight(0.35);
        split.setBorder(BorderFactory.createEmptyBorder());
        split.setOpaque(false);
        mainPanel.add(split, BorderLayout.CENTER);

        populateList();

        if (!installer.getInstallerConfig().configNames.isEmpty()) {
            modpackList.setSelectedIndex(0);
            showDetail(installer.getInstallerConfig().configNames.get(0));
        } else {
            detailCardLayout.show(detailContainer, "NONE");
        }
    }

    private JPanel buildLeftPanel() {
        JPanel panel = new JPanel(new BorderLayout());
        panel.setOpaque(false);
        panel.setBorder(new EmptyBorder(14, 14, 14, 7));

        JPanel header = new JPanel(new BorderLayout(8, 0));
        header.setOpaque(false);
        header.setBorder(new EmptyBorder(0, 0, 10, 0));

        JLabel title = new JLabel("Modpacks disponibles");
        title.setFont(ThemeManager.getBoldFont());

        refreshButton = GuiFactory.createStyledButton("Actualizar", 90, 28);
        header.add(title, BorderLayout.CENTER);
        header.add(refreshButton, BorderLayout.EAST);
        panel.add(header, BorderLayout.NORTH);

        listModel = new DefaultListModel<>();
        modpackList = new JList<>(listModel);
        modpackList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        modpackList.setCellRenderer(new ModpackCellRenderer());
        modpackList.setFixedCellHeight(76);

        JScrollPane scroll = new JScrollPane(modpackList);
        scroll.setBorder(BorderFactory.createEmptyBorder());
        panel.add(scroll, BorderLayout.CENTER);
        return panel;
    }

    private JPanel buildRightPanel() {
        JPanel panel = new JPanel(new BorderLayout());
        panel.setOpaque(false);
        panel.setBorder(new EmptyBorder(14, 7, 14, 14));

        detailCardLayout = new CardLayout();
        detailContainer  = new JPanel(detailCardLayout);
        detailContainer.setOpaque(false);

        JPanel nonePanel = new JPanel(new GridBagLayout());
        nonePanel.setOpaque(false);
        JLabel noneLabel = new JLabel("Selecciona un modpack de la lista");
        noneLabel.setFont(ThemeManager.getNormalFont());
        noneLabel.setForeground(Color.GRAY);
        nonePanel.add(noneLabel);

        detailContainer.add(nonePanel, "NONE");
        detailContainer.add(buildDetailPanel(), "DETAIL");
        panel.add(detailContainer, BorderLayout.CENTER);
        return panel;
    }

    private JPanel buildDetailPanel() {
        JPanel panel = new JPanel(new BorderLayout(0, 0));
        panel.setOpaque(false);

        JPanel topPanel = new JPanel(new BorderLayout(16, 0));
        topPanel.setOpaque(false);
        topPanel.setBorder(new EmptyBorder(8, 8, 14, 8));

        iconLabel = new JLabel();
        iconLabel.setPreferredSize(new Dimension(72, 72));
        iconLabel.setMinimumSize(new Dimension(72, 72));
        iconLabel.setHorizontalAlignment(SwingConstants.CENTER);
        iconLabel.setVerticalAlignment(SwingConstants.CENTER);
        iconLabel.setOpaque(true);
        topPanel.add(iconLabel, BorderLayout.WEST);

        JPanel nameMeta = new JPanel();
        nameMeta.setLayout(new BoxLayout(nameMeta, BoxLayout.Y_AXIS));
        nameMeta.setOpaque(false);

        nameLabel = new JLabel();
        nameLabel.setFont(new Font("Segoe UI", Font.BOLD, 17));
        nameLabel.setAlignmentX(Component.LEFT_ALIGNMENT);

        versionLoaderLabel = new JLabel();
        versionLoaderLabel.setFont(ThemeManager.getSmallFont());
        versionLoaderLabel.setForeground(Color.GRAY);
        versionLoaderLabel.setAlignmentX(Component.LEFT_ALIGNMENT);

        nameMeta.add(Box.createVerticalStrut(12));
        nameMeta.add(nameLabel);
        nameMeta.add(Box.createVerticalStrut(6));
        nameMeta.add(versionLoaderLabel);
        topPanel.add(nameMeta, BorderLayout.CENTER);

        descriptionArea = new JTextArea();
        descriptionArea.setEditable(false);
        descriptionArea.setLineWrap(true);
        descriptionArea.setWrapStyleWord(true);
        descriptionArea.setFont(ThemeManager.getNormalFont());
        descriptionArea.setOpaque(false);
        descriptionArea.setBorder(new EmptyBorder(6, 10, 8, 10));
        descriptionArea.setFocusable(false);

        JScrollPane descScroll = new JScrollPane(descriptionArea);
        descScroll.setBorder(BorderFactory.createEmptyBorder());
        descScroll.setOpaque(false);
        descScroll.getViewport().setOpaque(false);

        JPanel installPanel = new JPanel(new GridBagLayout());
        installPanel.setOpaque(false);
        installPanel.setBorder(new EmptyBorder(10, 8, 8, 8));

        GridBagConstraints gbc = new GridBagConstraints();
        gbc.insets = new Insets(6, 4, 6, 4);
        gbc.anchor = GridBagConstraints.WEST;

        gbc.gridx = 0; gbc.gridy = 0; gbc.fill = GridBagConstraints.NONE; gbc.weightx = 0;
        JLabel dirLabel = new JLabel("Directorio:");
        dirLabel.setFont(ThemeManager.getBoldFont());
        installPanel.add(dirLabel, gbc);

        gbc.gridx = 1; gbc.fill = GridBagConstraints.HORIZONTAL; gbc.weightx = 1.0;
        pathField = GuiFactory.createStyledTextField(installer.getGameDir().toString());
        installPanel.add(pathField, gbc);

        gbc.gridx = 2; gbc.fill = GridBagConstraints.NONE; gbc.weightx = 0;
        browseButton = GuiFactory.createStyledButton("Buscar", 90, 34);
        installPanel.add(browseButton, gbc);

        gbc.gridx = 0; gbc.gridy = 1; gbc.gridwidth = 3; gbc.anchor = GridBagConstraints.CENTER;
        gbc.insets = new Insets(14, 4, 4, 4);
        installButton = GuiFactory.createStyledButton("Instalar modpack", 190, 44);
        installPanel.add(installButton, gbc);

        panel.add(topPanel,    BorderLayout.NORTH);
        panel.add(descScroll,  BorderLayout.CENTER);
        panel.add(installPanel, BorderLayout.SOUTH);
        return panel;
    }

    private void populateList() {
        listModel.clear();
        for (String name : installer.getInstallerConfig().configNames) {
            listModel.addElement(name);
        }
    }

    private void showDetail(String modpackName) {
        InstallerConfig config = installer.getInstallerConfig();
        DownloadConfig dc = config.configs.get(modpackName);
        if (dc == null) { detailCardLayout.show(detailContainer, "NONE"); return; }

        installer.selectedInstall = modpackName;
        nameLabel.setText(dc.name);

        String vlText = buildVersionLoaderText(dc.version, dc.loader);
        versionLoaderLabel.setText(vlText.isEmpty() ? " " : vlText);

        descriptionArea.setText(dc.description.isEmpty() ? "Sin descripción disponible." : dc.description);
        descriptionArea.setCaretPosition(0);

        String initials = modpackName.substring(0, Math.min(2, modpackName.length())).toUpperCase();
        iconLabel.setIcon(null);
        iconLabel.setText(initials);
        iconLabel.setFont(new Font("Segoe UI", Font.BOLD, 22));
        iconLabel.setHorizontalAlignment(SwingConstants.CENTER);

        ThemeManager.Colors colors = ThemeManager.getCurrentColors();
        iconLabel.setBackground(colors.fieldBackground);
        iconLabel.setForeground(colors.text);

        if (!dc.iconUrl.isEmpty()) {
            ImageIcon cached = iconCache.get(dc.iconUrl);
            if (cached != null) {
                iconLabel.setIcon(cached);
                iconLabel.setText("");
            } else {
                loadIconForDetail(dc.iconUrl, modpackName);
            }
        }

        detailCardLayout.show(detailContainer, "DETAIL");
    }

    private String buildVersionLoaderText(String version, String loader) {
        if (!version.isEmpty() && !loader.isEmpty()) return version + "  ·  " + capitalize(loader);
        if (!version.isEmpty()) return version;
        if (!loader.isEmpty()) return capitalize(loader);
        return "";
    }

    private void loadIconForDetail(String url, String modpackName) {
        if (loadingIcons.contains(url)) return;
        loadingIcons.add(url);
        CompletableFuture.runAsync(() -> {
            ImageIcon icon = tryLoadIcon(url, 72);
            iconCache.put(url, icon);
            loadingIcons.remove(url);
            SwingUtilities.invokeLater(() -> {
                String sel = modpackList.getSelectedValue();
                if (modpackName.equals(sel) && icon != null) {
                    iconLabel.setIcon(icon);
                    iconLabel.setText("");
                }
                modpackList.repaint();
            });
        }, ICON_EXECUTOR);
    }

    private void loadIconForList(String url, JList<?> list) {
        if (iconCache.containsKey(url) || loadingIcons.contains(url)) return;
        loadingIcons.add(url);
        CompletableFuture.runAsync(() -> {
            ImageIcon icon = tryLoadIcon(url, 72);
            iconCache.put(url, icon);
            loadingIcons.remove(url);
            SwingUtilities.invokeLater(list::repaint);
        }, ICON_EXECUTOR);
    }

    private ImageIcon tryLoadIcon(String url, int size) {
        try {
            okhttp3.Request req = new okhttp3.Request.Builder()
                    .url(url)
                    .addHeader("User-Agent", "MateoF24-ModpackInstaller/3.0")
                    .addHeader("Accept", "image/*")
                    .build();
            byte[] data;
            try (okhttp3.Response resp = ICON_CLIENT.newCall(req).execute()) {
                if (!resp.isSuccessful() || resp.body() == null) return null;
                data = resp.body().bytes();
            }
            if (data.length < 4) return null;
            javax.imageio.ImageIO.setUseCache(false);
            BufferedImage src = javax.imageio.ImageIO.read(new ByteArrayInputStream(data));
            if (src == null) return null;
            BufferedImage scaled = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
            Graphics2D g2 = scaled.createGraphics();
            g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            g2.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
            g2.drawImage(src, 0, 0, size, size, null);
            g2.dispose();
            return new ImageIcon(scaled);
        } catch (Exception e) {
            return null;
        }
    }

    private String capitalize(String s) {
        if (s == null || s.isEmpty()) return s;
        return Character.toUpperCase(s.charAt(0)) + s.substring(1).toLowerCase();
    }

    private void setupEventHandlers() {
        modpackList.addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting()) {
                String sel = modpackList.getSelectedValue();
                if (sel != null) showDetail(sel);
            }
        });
        refreshButton.addActionListener(e -> refreshModpacks());
        browseButton.addActionListener(e -> browseDirectory());
        installButton.addActionListener(e -> performInstall());
    }

    public void applyConfig(InstallerConfig config) {
        if (config == null || config.configNames.isEmpty()) return;
        installer.updateInstallerConfig(config);
        installer.selectedInstall = config.configNames.get(0);
        populateList();
        modpackList.setSelectedIndex(0);
        showDetail(config.configNames.get(0));
    }

    private void refreshModpacks() {
        refreshButton.setEnabled(false);
        refreshButton.setText("…");
        SwingWorker<InstallerConfig, Void> worker = new SwingWorker<>() {
            @Override
            protected InstallerConfig doInBackground() {
                try {
                    JsonObject obj = RemoteConfigLoader.loadAndValidateRemoteConfig();
                    if (obj != null) return ConfigParser.parse(obj);
                } catch (Exception ignored) {}
                InputStream s = BundleInstaller.class.getClassLoader().getResourceAsStream("installer_config.json");
                if (s != null) {
                    try {
                        JsonObject obj = new Gson().fromJson(new InputStreamReader(s, StandardCharsets.UTF_8), JsonObject.class);
                        return ConfigParser.parse(obj);
                    } catch (Exception ignored) {}
                }
                return null;
            }
            @Override
            protected void done() {
                refreshButton.setEnabled(true);
                refreshButton.setText("Actualizar");
                try {
                    InstallerConfig cfg = get();
                    if (cfg != null && !cfg.configNames.isEmpty()) applyConfig(cfg);
                } catch (Exception ignored) {}
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
            public void onProgress(long done, long total, double speed, String item, String phase) {
                final int pct = total > 0 ? (int) ((done * 100) / total) : 0;
                SwingUtilities.invokeLater(() -> {
                    progressComponents.progressBar.setValue(Math.min(100, pct));
                    if ("Descarga".equals(phase)) {
                        progressComponents.progressLabel.setText(String.format("%d%% - %s (%s / %s)",
                                pct, formatSpeed(speed), formatBytes(done),
                                total > 0 ? formatBytes(total) : "Desconocido"));
                        progressComponents.statusLabel.setText("Descargando: " + item);
                    } else if ("Extracción".equals(phase)) {
                        progressComponents.progressLabel.setText(String.format("%d%% — %.1f archivos/s (%d / %d)", pct, speed, done, total));
                        progressComponents.statusLabel.setText("Extrayendo: " + item);
                    }
                });
            }
            @Override
            public void onPhaseComplete(String phaseName) {
                SwingUtilities.invokeLater(() -> progressComponents.statusLabel.setText(phaseName + " completada"));
            }
            @Override
            public void onAllComplete() {
                SwingUtilities.invokeLater(() -> {
                    progressComponents.statusLabel.setText("¡Instalación completada!");
                    progressComponents.progressBar.setValue(100);
                    progressComponents.progressLabel.setText("100% — ¡Completado!");
                });
            }
        };

        SwingWorker<Boolean, Void> worker = new SwingWorker<>() {
            private String errorMessage;
            @Override
            protected Boolean doInBackground() {
                try {
                    SwingUtilities.invokeLater(() -> {
                        progressComponents.statusLabel.setText("Preparando instalación...");
                        progressComponents.progressBar.setValue(0);
                        progressComponents.progressLabel.setText("0% — Preparando...");
                    });
                    installer.install(callback);
                    SwingUtilities.invokeLater(() -> {
                        progressComponents.progressBar.setValue(95);
                        progressComponents.statusLabel.setText("Procesando archivos...");
                        progressComponents.progressLabel.setText("95% — Finalizando...");
                    });
                    Thread.sleep(500);
                    SwingUtilities.invokeLater(() -> {
                        progressComponents.progressBar.setValue(100);
                        progressComponents.statusLabel.setText("¡Instalación completada!");
                        progressComponents.progressLabel.setText("100% — ¡Completado!");
                    });
                    Thread.sleep(1000);
                    return true;
                } catch (IOException | DownloadException e) {
                    errorMessage = buildErrorMessage(e);
                    return false;
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return false;
                }
            }
            @Override
            protected void done() {
                try {
                    if (get()) showResult("¡" + installer.selectedInstall + " instalado exitosamente!", ThemeManager.getCurrentColors().success);
                    else showResult(errorMessage != null ? errorMessage : "Error desconocido.", Color.RED);
                } catch (Exception ex) {
                    showResult("Error inesperado: " + ex.getMessage(), Color.RED);
                }
            }
        };
        worker.execute();
    }

    private String buildErrorMessage(Exception e) {
        if (e.getMessage() != null && e.getMessage().contains("Errores durante la descarga")) {
            return "No se pudo descargar el modpack.\n\nPosibles causas:\n" +
                    "• Conexión inestable\n• Firewall/Antivirus bloqueando la descarga\n" +
                    "• Servidor temporalmente no disponible\n\nIntenta nuevamente en unos minutos.";
        }
        return "Error durante la instalación:\n\n" + e.getMessage();
    }

    private String formatBytes(long bytes) {
        if (bytes < 1024) return bytes + " B";
        int exp = (int) (Math.log(bytes) / Math.log(1024));
        return String.format("%.1f %sB", bytes / Math.pow(1024, exp), "KMGTPE".charAt(exp - 1));
    }

    private String formatSpeed(double bps) { return formatBytes((long) bps) + "/s"; }

    private void showMainPanel()    { ((CardLayout) getLayout()).show(this, "MAIN"); }
    private void showProgressPanel(){ ((CardLayout) getLayout()).show(this, "PROGRESS"); }
    private void showResult(String msg, Color color) {
        resultComponents.show(msg, color);
        ((CardLayout) getLayout()).show(this, "RESULT");
    }

    public void updateTheme() {
        ThemeManager.Colors colors = ThemeManager.getCurrentColors();

        if (modpackList != null)     modpackList.setBackground(colors.background);
        if (descriptionArea != null) { descriptionArea.setBackground(colors.background); descriptionArea.setForeground(colors.text); }
        if (nameLabel != null)       nameLabel.setForeground(colors.text);
        if (versionLoaderLabel != null) versionLoaderLabel.setForeground(Color.GRAY);
        if (iconLabel != null)       { iconLabel.setBackground(colors.fieldBackground); iconLabel.setForeground(colors.text); }
        if (pathField != null)       { pathField.setBackground(colors.fieldBackground); pathField.setForeground(colors.text); pathField.setCaretColor(colors.text); }
        if (progressComponents != null) {
            progressComponents.statusLabel.setForeground(colors.text);
            progressComponents.progressLabel.setForeground(new Color(180, 180, 180));
        }
        updatePanelTheme(mainPanel, colors);
        if (progressComponents != null) updatePanelTheme(progressComponents.panel, colors);
        if (resultComponents != null)   updatePanelTheme(resultComponents.panel, colors);
        repaint();
    }

    private void updatePanelTheme(Component comp, ThemeManager.Colors colors) {
        if (comp instanceof JLabel)  { comp.setForeground(colors.text); }
        else if (comp instanceof Container c) { for (Component child : c.getComponents()) updatePanelTheme(child, colors); }
    }

    private class ModpackCellRenderer extends DefaultListCellRenderer {
        @Override
        public Component getListCellRendererComponent(JList<?> list, Object value, int index,
                                                      boolean isSelected, boolean cellHasFocus) {
            JPanel cell = new JPanel(new BorderLayout(10, 0));
            cell.setBorder(new EmptyBorder(10, 12, 10, 12));

            if (value instanceof String name) {
                ThemeManager.Colors colors = ThemeManager.getCurrentColors();
                cell.setBackground(isSelected ? colors.primary.darker()
                        : (index % 2 == 0 ? colors.background : colors.panelBackground));
                cell.setOpaque(true);

                DownloadConfig dc = installer.getInstallerConfig().configs.get(name);

                JLabel iconLbl = new JLabel();
                iconLbl.setPreferredSize(new Dimension(54, 54));
                iconLbl.setMinimumSize(new Dimension(54, 54));
                iconLbl.setHorizontalAlignment(SwingConstants.CENTER);
                iconLbl.setFont(new Font("Segoe UI", Font.BOLD, 16));
                iconLbl.setOpaque(true);
                iconLbl.setBackground(colors.fieldBackground);
                iconLbl.setForeground(isSelected ? Color.WHITE : colors.text);

                if (dc != null && !dc.iconUrl.isEmpty()) {
                    ImageIcon cached = iconCache.get(dc.iconUrl);
                    if (cached != null) {
                        iconLbl.setIcon(cached);
                    } else {
                        iconLbl.setText(name.substring(0, Math.min(2, name.length())).toUpperCase());
                        loadIconForList(dc.iconUrl, list);
                    }
                } else {
                    iconLbl.setText(name.substring(0, Math.min(2, name.length())).toUpperCase());
                }
                cell.add(iconLbl, BorderLayout.WEST);

                JPanel text = new JPanel(new GridLayout(3, 1, 0, 1));
                text.setOpaque(false);

                JLabel nameLbl = new JLabel(name);
                nameLbl.setFont(ThemeManager.getBoldFont());
                nameLbl.setForeground(isSelected ? Color.WHITE : colors.text);

                String desc = dc != null && !dc.description.isEmpty()
                        ? (dc.description.length() > 72 ? dc.description.substring(0, 69) + "…" : dc.description) : "";
                JLabel descLbl = new JLabel(desc);
                descLbl.setFont(ThemeManager.getSmallFont());
                descLbl.setForeground(isSelected ? new Color(210, 210, 210) : Color.GRAY);

                String vl = dc != null ? buildVersionLoaderText(dc.version, dc.loader) : "";
                JLabel vlLbl = new JLabel(vl);
                vlLbl.setFont(ThemeManager.getSmallFont());
                vlLbl.setForeground(isSelected ? new Color(180, 180, 180) : new Color(130, 130, 130));

                text.add(nameLbl);
                text.add(descLbl);
                text.add(vlLbl);
                cell.add(text, BorderLayout.CENTER);
            }
            return cell;
        }
    }
}