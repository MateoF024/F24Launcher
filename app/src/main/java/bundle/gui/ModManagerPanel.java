package bundle.gui;

import bundle.instance.GameInstance;
import bundle.instance.InstanceManager;
import bundle.mods.ModResult;
import bundle.mods.ModSearchService;
import bundle.mods.ModSource;
import bundle.util.HttpConnectionPool;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.List;
import java.util.concurrent.CompletableFuture;

public class ModManagerPanel extends JPanel {

    private final InstanceManager instanceManager;
    private final ModSearchService searchService = new ModSearchService();
    private final Map<String, ImageIcon> iconCache = new HashMap<>();
    private final Set<String> loadingIcons = Collections.synchronizedSet(new HashSet<>());
    private final Map<String, String> categoryMap = new LinkedHashMap<>();

    private JComboBox<GameInstance> instanceCombo;
    private JComboBox<ModSource> sourceCombo;
    private JComboBox<String> categoryCombo;
    private JTextField searchField;
    private JButton searchBtn;

    private JComboBox<String> loaderCombo;

    private DefaultListModel<ModResult> resultsModel;
    private JList<ModResult> resultsList;

    private DefaultListModel<String> installedModel;
    private JList<String> installedList;

    private JLabel statusLabel;
    private JLabel pageLabel;
    private JButton prevBtn;
    private JButton nextBtn;

    private int currentPage = 0;
    private int totalPages = 1;

    public ModManagerPanel(InstanceManager instanceManager) {
        this.instanceManager = instanceManager;
        setLayout(new BorderLayout());
        setOpaque(false);
        buildUI();
        updateTheme();
        refreshInstances();
    }

    private void buildUI() {
        JPanel northPanel = new JPanel(new BorderLayout());
        northPanel.setOpaque(false);
        northPanel.add(buildInstanceBar(), BorderLayout.NORTH);
        northPanel.add(buildSearchBar(), BorderLayout.SOUTH);

        add(northPanel, BorderLayout.NORTH);
        add(buildCenterPanel(), BorderLayout.CENTER);
        add(buildBottomBar(), BorderLayout.SOUTH);
    }

    private JPanel buildInstanceBar() {
        JPanel bar = new JPanel(new GridBagLayout());
        bar.setOpaque(false);
        bar.setBorder(new EmptyBorder(12, 16, 4, 16));

        GridBagConstraints gbc = new GridBagConstraints();
        gbc.insets = new Insets(0, 4, 0, 4);
        gbc.anchor = GridBagConstraints.WEST;
        gbc.fill = GridBagConstraints.HORIZONTAL;

        gbc.gridx = 0; gbc.weightx = 0;
        JLabel lbl = new JLabel("Instancia:");
        lbl.setFont(ThemeManager.getBoldFont());
        bar.add(lbl, gbc);

        gbc.gridx = 1; gbc.weightx = 1.0;
        instanceCombo = new JComboBox<>();
        instanceCombo.setFont(ThemeManager.getNormalFont());
        instanceCombo.addActionListener(e -> {
            syncLoaderCombo();
            refreshInstalledMods();
            doSearch(0);
        });
        bar.add(instanceCombo, gbc);

        gbc.gridx = 2; gbc.weightx = 0; gbc.fill = GridBagConstraints.NONE;
        JButton addBtn = GuiFactory.createStyledButton("+ Agregar carpeta", 150, 34);
        addBtn.addActionListener(e -> addInstance());
        bar.add(addBtn, gbc);

        gbc.gridx = 3;
        JButton removeBtn = buildRedButton("Quitar", 80, 34);
        removeBtn.addActionListener(e -> removeInstance());
        bar.add(removeBtn, gbc);

        return bar;
    }

    private JPanel buildSearchBar() {
        JPanel bar = new JPanel(new GridBagLayout());
        bar.setOpaque(false);
        bar.setBorder(new EmptyBorder(4, 16, 8, 16));

        GridBagConstraints gbc = new GridBagConstraints();
        gbc.insets = new Insets(0, 4, 0, 4);
        gbc.anchor = GridBagConstraints.WEST;
        gbc.fill = GridBagConstraints.HORIZONTAL;

        gbc.gridx = 0; gbc.weightx = 0;
        JLabel srcLbl = new JLabel("Fuente:");
        srcLbl.setFont(ThemeManager.getBoldFont());
        bar.add(srcLbl, gbc);

        gbc.gridx = 1; gbc.weightx = 0.15;
        sourceCombo = new JComboBox<>(ModSource.values());
        sourceCombo.setFont(ThemeManager.getNormalFont());
        sourceCombo.addActionListener(e -> { loadCategories(); doSearch(0); });
        bar.add(sourceCombo, gbc);

        gbc.gridx = 2; gbc.weightx = 0;
        JLabel loaderLbl = new JLabel("Loader:");
        loaderLbl.setFont(ThemeManager.getBoldFont());
        bar.add(loaderLbl, gbc);

        gbc.gridx = 3; gbc.weightx = 0.15;
        loaderCombo = new JComboBox<>(new String[]{"Auto", "fabric", "forge", "neoforge", "quilt"});
        loaderCombo.setFont(ThemeManager.getNormalFont());
        loaderCombo.addActionListener(e -> doSearch(0));
        bar.add(loaderCombo, gbc);

        gbc.gridx = 4; gbc.weightx = 0;
        JLabel catLbl = new JLabel("Categoría:");
        catLbl.setFont(ThemeManager.getBoldFont());
        bar.add(catLbl, gbc);

        gbc.gridx = 5; gbc.weightx = 0.2;
        categoryCombo = new JComboBox<>(new String[]{"Cualquiera"});
        categoryCombo.setFont(ThemeManager.getNormalFont());
        categoryCombo.addActionListener(e -> doSearch(0));
        bar.add(categoryCombo, gbc);

        gbc.gridx = 6; gbc.weightx = 0;
        JLabel searchLbl = new JLabel("Buscar:");
        searchLbl.setFont(ThemeManager.getBoldFont());
        bar.add(searchLbl, gbc);

        gbc.gridx = 7; gbc.weightx = 1.0;
        searchField = GuiFactory.createStyledTextField("");
        searchField.addActionListener(e -> doSearch(0));
        bar.add(searchField, gbc);

        gbc.gridx = 8; gbc.weightx = 0; gbc.fill = GridBagConstraints.NONE;
        searchBtn = GuiFactory.createStyledButton("Buscar", 90, 34);
        searchBtn.addActionListener(e -> doSearch(0));
        bar.add(searchBtn, gbc);

        return bar;
    }

    private JSplitPane buildCenterPanel() {
        JPanel leftPanel = new JPanel(new BorderLayout());
        leftPanel.setOpaque(false);

        resultsModel = new DefaultListModel<>();
        resultsList = new JList<>(resultsModel);
        resultsList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        resultsList.setCellRenderer(new ModCellRenderer());
        resultsList.setFixedCellHeight(76);

        JScrollPane resultsScroll = new JScrollPane(resultsList);
        resultsScroll.setBorder(BorderFactory.createEmptyBorder());

        leftPanel.add(resultsScroll, BorderLayout.CENTER);
        leftPanel.add(buildPaginationBar(), BorderLayout.SOUTH);

        JPanel rightPanel = new JPanel(new BorderLayout());
        rightPanel.setOpaque(false);

        JLabel installedTitle = new JLabel("Mods instalados");
        installedTitle.setFont(ThemeManager.getBoldFont());
        installedTitle.setBorder(new EmptyBorder(8, 8, 6, 8));
        rightPanel.add(installedTitle, BorderLayout.NORTH);

        installedModel = new DefaultListModel<>();
        installedList = new JList<>(installedModel);
        installedList.setFont(ThemeManager.getSmallFont());
        installedList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        installedList.setCellRenderer(new InstalledModCellRenderer());
        installedList.setFixedCellHeight(30);

        JScrollPane installedScroll = new JScrollPane(installedList);
        installedScroll.setBorder(BorderFactory.createEmptyBorder());
        rightPanel.add(installedScroll, BorderLayout.CENTER);

        JButton uninstallBtn = buildRedButton("Desinstalar seleccionado", 220, 34);
        uninstallBtn.addActionListener(e -> uninstallSelected());
        JPanel uninstallWrapper = new JPanel(new BorderLayout());
        uninstallWrapper.setOpaque(false);
        uninstallWrapper.setBorder(new EmptyBorder(6, 8, 8, 8));
        uninstallWrapper.add(uninstallBtn, BorderLayout.CENTER);
        rightPanel.add(uninstallWrapper, BorderLayout.SOUTH);

        JSplitPane split = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, leftPanel, rightPanel);
        split.setResizeWeight(0.7);
        split.setDividerSize(4);
        split.setBorder(BorderFactory.createEmptyBorder());
        split.setOpaque(false);

        return split;
    }

    private JPanel buildPaginationBar() {
        JPanel bar = new JPanel(new FlowLayout(FlowLayout.CENTER, 8, 6));
        bar.setOpaque(false);

        prevBtn = GuiFactory.createStyledButton("< Anterior", 100, 30);
        prevBtn.setEnabled(false);
        prevBtn.addActionListener(e -> { if (currentPage > 0) doSearch(currentPage - 1); });

        pageLabel = new JLabel("Pág. 1 / 1");
        pageLabel.setFont(ThemeManager.getSmallFont());

        nextBtn = GuiFactory.createStyledButton("Siguiente >", 100, 30);
        nextBtn.setEnabled(false);
        nextBtn.addActionListener(e -> { if (currentPage < totalPages - 1) doSearch(currentPage + 1); });

        bar.add(prevBtn);
        bar.add(pageLabel);
        bar.add(nextBtn);
        return bar;
    }

    private JPanel buildBottomBar() {
        JPanel bar = new JPanel(new BorderLayout(10, 0));
        bar.setOpaque(false);
        bar.setBorder(new EmptyBorder(6, 16, 10, 16));

        statusLabel = new JLabel(" ");
        statusLabel.setFont(ThemeManager.getSmallFont());

        JButton installBtn = GuiFactory.createStyledButton("Instalar mod seleccionado", 220, 36);
        installBtn.addActionListener(e -> installSelected());

        bar.add(statusLabel, BorderLayout.CENTER);
        bar.add(installBtn, BorderLayout.EAST);
        return bar;
    }

    private void addInstance() {
        JFileChooser fc = new JFileChooser();
        fc.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
        fc.setDialogTitle("Seleccionar carpeta de instancia");
        if (fc.showOpenDialog(this) != JFileChooser.APPROVE_OPTION) return;

        Path selected = fc.getSelectedFile().toPath();
        if (!Files.exists(selected.resolve("mods"))) {
            int opt = JOptionPane.showConfirmDialog(this,
                    "La carpeta no contiene una subcarpeta 'mods'.\n¿Crearla automáticamente?",
                    "Sin carpeta mods", JOptionPane.YES_NO_OPTION);
            if (opt != JOptionPane.YES_OPTION) return;
        }

        if (!instanceManager.addInstance(selected)) {
            JOptionPane.showMessageDialog(this, "La instancia ya está en la lista o no se pudo agregar.",
                    "Aviso", JOptionPane.WARNING_MESSAGE);
            return;
        }
        refreshInstances();
        for (int i = 0; i < instanceCombo.getItemCount(); i++) {
            if (instanceCombo.getItemAt(i).path.equals(selected.toString())) {
                instanceCombo.setSelectedIndex(i);
                break;
            }
        }
    }

    private void removeInstance() {
        GameInstance inst = (GameInstance) instanceCombo.getSelectedItem();
        if (inst == null) return;
        int opt = JOptionPane.showConfirmDialog(this,
                "¿Quitar \"" + inst.name + "\" de la lista?\n(Los archivos no se eliminarán)",
                "Quitar instancia", JOptionPane.YES_NO_OPTION);
        if (opt != JOptionPane.YES_OPTION) return;
        instanceManager.removeInstance(inst.path);
        refreshInstances();
    }

    public void refreshInstances() {
        GameInstance selected = (GameInstance) instanceCombo.getSelectedItem();
        instanceCombo.removeAllItems();
        for (GameInstance inst : instanceManager.getInstances()) instanceCombo.addItem(inst);
        if (selected != null) {
            for (int i = 0; i < instanceCombo.getItemCount(); i++) {
                if (instanceCombo.getItemAt(i).path.equals(selected.path)) {
                    instanceCombo.setSelectedIndex(i);
                    break;
                }
            }
        }
        syncLoaderCombo();
        refreshInstalledMods();
        if (categoryMap.isEmpty()) loadCategories();
    }

    private void syncLoaderCombo() {
        GameInstance inst = (GameInstance) instanceCombo.getSelectedItem();
        if (inst == null || inst.loader == null || inst.loader.isEmpty()
                || inst.loader.equalsIgnoreCase("vanilla")
                || inst.loader.equalsIgnoreCase("unknown")) {
            loaderCombo.setSelectedItem("Auto");
            return;
        }
        String detected = inst.loader.toLowerCase();
        for (int i = 0; i < loaderCombo.getItemCount(); i++) {
            if (loaderCombo.getItemAt(i).equalsIgnoreCase(detected)) {
                loaderCombo.setSelectedIndex(i);
                return;
            }
        }
        loaderCombo.setSelectedItem("Auto");
    }

    private void refreshInstalledMods() {
        installedModel.clear();
        GameInstance inst = (GameInstance) instanceCombo.getSelectedItem();
        if (inst == null || inst.path == null) return;
        Path modsDir = Path.of(inst.path, "mods");
        if (!Files.exists(modsDir)) return;
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(modsDir, "*.jar")) {
            List<String> names = new ArrayList<>();
            for (Path p : stream) names.add(p.getFileName().toString());
            Collections.sort(names);
            names.forEach(installedModel::addElement);
        } catch (IOException ignored) {}
    }

    private void uninstallSelected() {
        String selected = installedList.getSelectedValue();
        GameInstance inst = (GameInstance) instanceCombo.getSelectedItem();
        if (selected == null || inst == null) return;
        int opt = JOptionPane.showConfirmDialog(this,
                "¿Eliminar \"" + selected + "\" de la instancia?",
                "Desinstalar mod", JOptionPane.YES_NO_OPTION);
        if (opt != JOptionPane.YES_OPTION) return;
        try {
            Files.deleteIfExists(Path.of(inst.path, "mods", selected));
            refreshInstalledMods();
            statusLabel.setText("Desinstalado: " + selected);
        } catch (IOException e) {
            JOptionPane.showMessageDialog(this, "Error:\n" + e.getMessage(), "Error", JOptionPane.ERROR_MESSAGE);
        }
    }

    private void loadCategories() {
        ModSource source = (ModSource) sourceCombo.getSelectedItem();
        categoryCombo.setEnabled(false);
        categoryCombo.setModel(new DefaultComboBoxModel<>(new String[]{"Cargando..."}));
        categoryMap.clear();

        searchService.getCategories(source).thenAccept(map -> SwingUtilities.invokeLater(() -> {
            categoryMap.putAll(map);
            categoryCombo.setModel(new DefaultComboBoxModel<>(map.keySet().toArray(new String[0])));
            categoryCombo.setEnabled(true);
        }));
    }

    private void doSearch(int page) {
        if (sourceCombo == null || categoryCombo == null || searchField == null) return;

        GameInstance inst = (GameInstance) instanceCombo.getSelectedItem();

        String effectiveLoader = (String) loaderCombo.getSelectedItem();
        if ("Auto".equals(effectiveLoader)) {
            effectiveLoader = (inst != null && inst.loader != null
                    && !inst.loader.equalsIgnoreCase("vanilla")
                    && !inst.loader.equalsIgnoreCase("unknown")
                    && !inst.loader.isEmpty()) ? inst.loader : null;
        }

        String effectiveMcVersion = (inst != null && inst.minecraftVersion != null
                && !inst.minecraftVersion.isEmpty()) ? inst.minecraftVersion : null;

        if (effectiveLoader == null) {
            resultsModel.clear();
            statusLabel.setText("⚠ Selecciona un loader en el selector para filtrar mods.");
            prevBtn.setEnabled(false);
            nextBtn.setEnabled(false);
            pageLabel.setText("Pág. 1 / 1");
            searchBtn.setEnabled(true);
            return;
        }

        ModSource source = (ModSource) sourceCombo.getSelectedItem();
        String selectedCategory = (String) categoryCombo.getSelectedItem();
        String categoryId = (selectedCategory != null && !selectedCategory.equals("Cargando..."))
                ? categoryMap.getOrDefault(selectedCategory, "") : "";
        String query = searchField.getText().trim();

        searchBtn.setEnabled(false);
        String loaderLabel = effectiveLoader;
        String versionLabel = effectiveMcVersion != null ? effectiveMcVersion : "cualquier versión";
        statusLabel.setText("Buscando para " + versionLabel + " · " + loaderLabel + "...");
        resultsModel.clear();
        currentPage = page;

        final String finalLoader = effectiveLoader;
        final String finalMcVersion = effectiveMcVersion;

        searchService.search(query, finalMcVersion, finalLoader, source, categoryId, page)
                .thenAccept(result -> SwingUtilities.invokeLater(() -> {
                    resultsModel.clear();
                    for (ModResult r : result.mods) resultsModel.addElement(r);
                    totalPages = Math.max(1, (int) Math.ceil((double) result.totalHits / 20));
                    pageLabel.setText("Pág. " + (currentPage + 1) + " / " + totalPages);
                    prevBtn.setEnabled(currentPage > 0);
                    nextBtn.setEnabled(currentPage < totalPages - 1);
                    statusLabel.setText(result.totalHits + " resultado(s) · " + versionLabel + " · " + loaderLabel);
                    searchBtn.setEnabled(true);
                }))
                .exceptionally(ex -> {
                    SwingUtilities.invokeLater(() -> {
                        statusLabel.setText("Error al buscar.");
                        searchBtn.setEnabled(true);
                    });
                    return null;
                });
    }

    private void installSelected() {
        ModResult mod = resultsList.getSelectedValue();
        GameInstance inst = (GameInstance) instanceCombo.getSelectedItem();
        if (mod == null) {
            JOptionPane.showMessageDialog(this, "Selecciona un mod de la lista.", "Aviso", JOptionPane.WARNING_MESSAGE);
            return;
        }
        if (inst == null || inst.path == null) {
            JOptionPane.showMessageDialog(this, "Selecciona una instancia.", "Aviso", JOptionPane.WARNING_MESSAGE);
            return;
        }

        String effectiveLoader = (String) loaderCombo.getSelectedItem();
        if ("Auto".equals(effectiveLoader)) {
            effectiveLoader = (inst.loader != null && !inst.loader.equalsIgnoreCase("vanilla")
                    && !inst.loader.equalsIgnoreCase("unknown") && !inst.loader.isEmpty())
                    ? inst.loader : null;
        }
        if (effectiveLoader == null) {
            JOptionPane.showMessageDialog(this,
                    "No se pudo determinar el loader. Selecciónalo manualmente en el selector.",
                    "Loader desconocido", JOptionPane.WARNING_MESSAGE);
            return;
        }

        final String finalLoader = effectiveLoader;
        final String finalMcVersion = (inst.minecraftVersion != null && !inst.minecraftVersion.isEmpty())
                ? inst.minecraftVersion : null;

        statusLabel.setText("Instalando " + mod.name + "...");
        searchBtn.setEnabled(false);
        searchService.installMod(mod, Path.of(inst.path), finalMcVersion, finalLoader)
                .thenAccept(dest -> SwingUtilities.invokeLater(() -> {
                    statusLabel.setText("Instalado: " + dest.getFileName());
                    searchBtn.setEnabled(true);
                    refreshInstalledMods();
                }))
                .exceptionally(ex -> {
                    SwingUtilities.invokeLater(() -> {
                        String msg = ex.getCause() != null ? ex.getCause().getMessage() : ex.getMessage();
                        statusLabel.setText("Error al instalar.");
                        searchBtn.setEnabled(true);
                        JOptionPane.showMessageDialog(this, "Error:\n" + msg, "Error", JOptionPane.ERROR_MESSAGE);
                    });
                    return null;
                });
    }

    private static final java.util.concurrent.ExecutorService ICON_EXECUTOR =
            java.util.concurrent.Executors.newFixedThreadPool(8);

    private static final okhttp3.OkHttpClient ICON_CLIENT = new okhttp3.OkHttpClient.Builder()
            .connectTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
            .readTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .build();

    private ImageIcon loadModIcon(String url, JList<?> list) {
        if (url == null || url.isEmpty()) return null;
        if (iconCache.containsKey(url)) return iconCache.get(url);
        if (loadingIcons.contains(url)) return null;
        loadingIcons.add(url);
        CompletableFuture.runAsync(() -> {
            ImageIcon result = tryLoadIcon(url);
            if (result == null && url.endsWith(".webp")) {
                String pngUrl = url.substring(0, url.length() - 5) + ".png";
                result = tryLoadIcon(pngUrl);
            }
            iconCache.put(url, result);
            loadingIcons.remove(url);
            SwingUtilities.invokeLater(list::repaint);
        }, ICON_EXECUTOR);
        return null;
    }

    private static final byte[] WEBP_RIFF = {0x52, 0x49, 0x46, 0x46};
    private static final byte[] WEBP_MARKER = {0x57, 0x45, 0x42, 0x50};

    private ImageIcon tryLoadIcon(String url) {
        try {
            okhttp3.Request req = new okhttp3.Request.Builder()
                    .url(url)
                    .addHeader("User-Agent", "MateoF24-ModpackInstaller/3.0")
                    .addHeader("Accept", "image/png,image/jpeg,image/gif,image/webp,image/*")
                    .build();
            byte[] data;
            try (okhttp3.Response resp = ICON_CLIENT.newCall(req).execute()) {
                if (!resp.isSuccessful() || resp.body() == null) return null;
                data = resp.body().bytes();
            }
            if (data.length < 12) return null;

            javax.imageio.ImageIO.setUseCache(false);
            BufferedImage src = null;

            boolean isWebP = isWebPBytes(data);
            if (isWebP) {
                java.util.Iterator<javax.imageio.ImageReader> readers =
                        javax.imageio.ImageIO.getImageReadersByMIMEType("image/webp");
                if (readers.hasNext()) {
                    javax.imageio.ImageReader reader = readers.next();
                    try (javax.imageio.stream.ImageInputStream iis =
                                 javax.imageio.ImageIO.createImageInputStream(new ByteArrayInputStream(data))) {
                        reader.setInput(iis);
                        src = reader.read(0);
                    } finally {
                        reader.dispose();
                    }
                }
            }

            if (src == null) {
                src = javax.imageio.ImageIO.read(new ByteArrayInputStream(data));
            }

            if (src == null) return null;

            BufferedImage scaled = new BufferedImage(48, 48, BufferedImage.TYPE_INT_ARGB);
            Graphics2D g2 = scaled.createGraphics();
            g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            g2.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
            g2.setComposite(AlphaComposite.Src);
            g2.drawImage(src, 0, 0, 48, 48, null);
            g2.dispose();
            return new ImageIcon(scaled);
        } catch (Exception e) {
            return null;
        }
    }

    private boolean isWebPBytes(byte[] data) {
        if (data.length < 12) return false;
        for (int i = 0; i < 4; i++) if (data[i] != WEBP_RIFF[i]) return false;
        for (int i = 0; i < 4; i++) if (data[i + 8] != WEBP_MARKER[i]) return false;
        return true;
    }

    private JButton buildRedButton(String text, int w, int h) {
        JButton btn = new JButton(text) {
            @Override
            protected void paintComponent(Graphics g) {
                Graphics2D g2 = (Graphics2D) g.create();
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                Color base = new Color(190, 50, 50);
                g2.setColor(getModel().isPressed() ? base.darker() : getModel().isRollover() ? base.brighter() : base);
                g2.fillRoundRect(0, 0, getWidth(), getHeight(), 10, 10);
                g2.setColor(Color.WHITE);
                g2.setFont(getFont());
                FontMetrics fm = g2.getFontMetrics();
                g2.drawString(getText(), (getWidth() - fm.stringWidth(getText())) / 2,
                        (getHeight() + fm.getAscent()) / 2 - 2);
                g2.dispose();
            }
        };
        btn.setFont(ThemeManager.getBoldFont());
        btn.setPreferredSize(new Dimension(w, h));
        btn.setFocusPainted(false);
        btn.setBorderPainted(false);
        btn.setContentAreaFilled(false);
        btn.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        return btn;
    }

    public void updateTheme() {
        ThemeManager.Colors colors = ThemeManager.getCurrentColors();
        if (statusLabel != null) statusLabel.setForeground(colors.text);
        if (instanceCombo != null) { instanceCombo.setBackground(colors.fieldBackground); instanceCombo.setForeground(colors.text); }
        if (sourceCombo != null) { sourceCombo.setBackground(colors.fieldBackground); sourceCombo.setForeground(colors.text); }
        if (categoryCombo != null) { categoryCombo.setBackground(colors.fieldBackground); categoryCombo.setForeground(colors.text); }
        if (searchField != null) { searchField.setBackground(colors.fieldBackground); searchField.setForeground(colors.text); searchField.setCaretColor(colors.text); }
        if (resultsList != null) resultsList.setBackground(colors.background);
        if (installedList != null) installedList.setBackground(colors.panelBackground);
        if (pageLabel != null) pageLabel.setForeground(colors.text);
        if (loaderCombo != null) { loaderCombo.setBackground(colors.fieldBackground); loaderCombo.setForeground(colors.text); }
        repaint();
    }

    private class ModCellRenderer extends DefaultListCellRenderer {
        @Override
        public Component getListCellRendererComponent(JList<?> list, Object value, int index,
                                                      boolean isSelected, boolean cellHasFocus) {
            JPanel cell = new JPanel(new BorderLayout(10, 0));
            cell.setBorder(new EmptyBorder(10, 12, 10, 12));
            if (value instanceof ModResult mod) {
                ThemeManager.Colors colors = ThemeManager.getCurrentColors();
                cell.setBackground(isSelected ? colors.primary.darker()
                        : (index % 2 == 0 ? colors.background : colors.panelBackground));
                cell.setOpaque(true);

                ImageIcon icon = loadModIcon(mod.iconUrl, list);
                JLabel iconLbl = new JLabel();
                iconLbl.setPreferredSize(new Dimension(48, 48));
                iconLbl.setOpaque(false);
                if (icon != null) iconLbl.setIcon(icon);
                cell.add(iconLbl, BorderLayout.WEST);

                JPanel text = new JPanel(new GridLayout(3, 1, 0, 1));
                text.setOpaque(false);

                JLabel name = new JLabel(mod.name);
                name.setFont(ThemeManager.getBoldFont());
                name.setForeground(isSelected ? Color.WHITE : colors.text);

                String desc = mod.description != null && mod.description.length() > 90
                        ? mod.description.substring(0, 87) + "..."
                        : (mod.description != null ? mod.description : "");
                JLabel descLbl = new JLabel(desc);
                descLbl.setFont(ThemeManager.getSmallFont());
                descLbl.setForeground(isSelected ? new Color(210, 210, 210) : Color.GRAY);

                JLabel dlLbl = new JLabel(fmt(mod.downloads) + " descargas · " + mod.source.displayName);
                dlLbl.setFont(ThemeManager.getSmallFont());
                dlLbl.setForeground(isSelected ? new Color(180, 180, 180) : new Color(130, 130, 130));

                text.add(name);
                text.add(descLbl);
                text.add(dlLbl);
                cell.add(text, BorderLayout.CENTER);
            }
            return cell;
        }

        private String fmt(long n) {
            if (n >= 1_000_000) return String.format("%.1fM", n / 1_000_000.0);
            if (n >= 1_000) return String.format("%.1fK", n / 1_000.0);
            return String.valueOf(n);
        }
    }

    private class InstalledModCellRenderer extends DefaultListCellRenderer {
        @Override
        public Component getListCellRendererComponent(JList<?> list, Object value, int index,
                                                      boolean isSelected, boolean cellHasFocus) {
            JPanel cell = new JPanel(new BorderLayout());
            cell.setBorder(new EmptyBorder(4, 8, 4, 8));
            cell.setOpaque(true);
            ThemeManager.Colors colors = ThemeManager.getCurrentColors();
            cell.setBackground(isSelected ? colors.primary.darker() : colors.panelBackground);
            JLabel lbl = new JLabel(value != null ? value.toString() : "");
            lbl.setFont(ThemeManager.getSmallFont());
            lbl.setForeground(isSelected ? Color.WHITE : colors.text);
            cell.add(lbl, BorderLayout.CENTER);
            return cell;
        }
    }
}