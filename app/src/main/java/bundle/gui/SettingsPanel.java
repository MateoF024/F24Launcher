package bundle.gui;

import bundle.installer.BundleInstaller;
import bundle.settings.AppSettings;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.border.TitledBorder;
import java.awt.*;

public class SettingsPanel extends JPanel {
    private final BundleInstaller installer;
    private final AppSettings appSettings;
    private final Runnable themeUpdateCallback;

    private JToggleButton themeToggle;
    private JLabel themeLabel;

    private JPanel foldersPanel;
    private JCheckBox[] folderCheckboxes;

    private JButton resetButton;
    private JToggleButton preserveSettingsToggle;
    private JLabel preserveLabel;

    private JPanel themePanel;
    private JPanel installerPanel;

    public SettingsPanel(BundleInstaller installer, Runnable themeUpdateCallback) {
        this.installer = installer;
        this.appSettings = AppSettings.getInstance();
        this.themeUpdateCallback = themeUpdateCallback;

        setLayout(new BoxLayout(this, BoxLayout.Y_AXIS));
        setOpaque(false);
        setBorder(new EmptyBorder(20, 20, 20, 20));

        createComponents();
        layoutComponents();
        setupEventHandlers();
        updateTheme();
    }

    private void createComponents() {
        themeToggle = createStyledToggle();
        themeLabel = new JLabel("Modo oscuro");
        themeLabel.setFont(ThemeManager.getNormalFont());

        createFoldersComponents();

        resetButton = createStyledButton("Restaurar opciones", 180, 36);
        preserveSettingsToggle = createStyledToggle();
        preserveLabel = new JLabel("Guardar configuración al cerrar");
        preserveLabel.setFont(ThemeManager.getNormalFont());
    }

    private void createFoldersComponents() {
        AppSettings.FolderType[] folders = AppSettings.FolderType.values();
        folderCheckboxes = new JCheckBox[folders.length];

        for (int i = 0; i < folders.length; i++) {
            AppSettings.FolderType folder = folders[i];
            folderCheckboxes[i] = createStyledCheckBox(folder.getDisplayName());
            folderCheckboxes[i].setSelected(appSettings.isFolderSelected(folder.getFolderName()));

            final String folderName = folder.getFolderName();
            int finalI = i;
            folderCheckboxes[i].addActionListener(e -> {
                appSettings.setFolderSelected(folderName, folderCheckboxes[finalI].isSelected());
            });
        }
    }

    private void layoutComponents() {

        themePanel = createSectionPanel("Configuración de Apariencia");
        JPanel themeContent = new JPanel(new FlowLayout(FlowLayout.LEFT));
        themeContent.setOpaque(false);
        themeContent.add(themeLabel);
        themeContent.add(Box.createHorizontalStrut(20));
        themeContent.add(themeToggle);
        themePanel.add(themeContent);

        add(themePanel);
        add(Box.createVerticalStrut(15));

        // Panel de carpetas
        foldersPanel = createSectionPanel("Carpetas a instalar");
        JPanel foldersGrid = new JPanel(new GridLayout(0, 2, 10, 5));
        foldersGrid.setOpaque(false);

        for (JCheckBox checkbox : folderCheckboxes) {
            foldersGrid.add(checkbox);
        }

        foldersPanel.add(foldersGrid);
        add(foldersPanel);
        add(Box.createVerticalStrut(15));

        // Panel del instalador
        installerPanel = createSectionPanel("Configuración del instalador");

        JPanel preserveContent = new JPanel(new FlowLayout(FlowLayout.LEFT));
        preserveContent.setOpaque(false);
        preserveContent.add(preserveLabel);
        preserveContent.add(Box.createHorizontalStrut(20));
        preserveContent.add(preserveSettingsToggle);

        JPanel resetContent = new JPanel(new FlowLayout(FlowLayout.CENTER));
        resetContent.setOpaque(false);
        resetContent.add(resetButton);

        installerPanel.add(preserveContent);
        installerPanel.add(Box.createVerticalStrut(10));
        installerPanel.add(resetContent);

        add(installerPanel);
        add(Box.createVerticalGlue());
    }

    private JPanel createSectionPanel(String title) {
        JPanel panel = new JPanel();
        panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
        panel.setOpaque(false);
        panel.setMaximumSize(new Dimension(Integer.MAX_VALUE, panel.getPreferredSize().height + 100));

        TitledBorder border = BorderFactory.createTitledBorder(title);
        border.setTitleFont(ThemeManager.getBoldFont());
        panel.setBorder(BorderFactory.createCompoundBorder(
                border,
                new EmptyBorder(10, 10, 10, 10)
        ));

        return panel;
    }

    private JToggleButton createStyledToggle() {
        JToggleButton toggle = new JToggleButton() {
            @Override
            protected void paintComponent(Graphics g) {
                Graphics2D g2 = (Graphics2D) g.create();
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

                int width = getWidth();
                int height = getHeight();
                int trackWidth = 50;
                int trackHeight = 24;
                int thumbSize = 20;

                int trackX = (width - trackWidth) / 2;
                int trackY = (height - trackHeight) / 2;

                ThemeManager.Colors colors = ThemeManager.getCurrentColors();

                g2.setColor(isSelected() ? colors.primary : colors.progressBackground);
                g2.fillRoundRect(trackX, trackY, trackWidth, trackHeight, trackHeight, trackHeight);

                int thumbX = isSelected() ?
                        trackX + trackWidth - thumbSize - 2 :
                        trackX + 2;
                int thumbY = trackY + 2;

                g2.setColor(Color.WHITE);
                g2.fillOval(thumbX, thumbY, thumbSize, thumbSize);

                g2.setColor(new Color(0, 0, 0, 30));
                g2.fillOval(thumbX + 1, thumbY + 1, thumbSize, thumbSize);

                g2.dispose();
            }
        };

        toggle.setPreferredSize(new Dimension(60, 30));
        toggle.setFocusPainted(false);
        toggle.setBorderPainted(false);
        toggle.setContentAreaFilled(false);
        toggle.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));

        return toggle;
    }

    private JCheckBox createStyledCheckBox(String text) {
        JCheckBox checkbox = new JCheckBox(text) {
            @Override
            protected void paintComponent(Graphics g) {
                Graphics2D g2 = (Graphics2D) g.create();
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

                ThemeManager.Colors colors = ThemeManager.getCurrentColors();

                if (getModel().isRollover()) {
                    g2.setColor(new Color(colors.primary.getRed(), colors.primary.getGreen(),
                            colors.primary.getBlue(), 30));
                    g2.fillRoundRect(0, 0, getWidth(), getHeight(), 8, 8);
                }

                int size = 18;
                int x = 5;
                int y = (getHeight() - size) / 2;

                g2.setColor(colors.fieldBackground);
                g2.fillRoundRect(x, y, size, size, 4, 4);

                g2.setColor(colors.border);
                g2.drawRoundRect(x, y, size, size, 4, 4);

                if (isSelected()) {
                    g2.setColor(colors.primary);
                    g2.fillRoundRect(x + 2, y + 2, size - 4, size - 4, 2, 2);

                    g2.setColor(Color.WHITE);
                    g2.setStroke(new BasicStroke(2));
                    g2.drawLine(x + 5, y + size/2, x + size/2, y + size - 6);
                    g2.drawLine(x + size/2, y + size - 6, x + size - 4, y + 4);
                }

                g2.setColor(colors.text);
                g2.setFont(getFont());
                FontMetrics fm = g2.getFontMetrics();
                int textX = x + size + 8;
                int textY = (getHeight() + fm.getAscent()) / 2 - 2;
                g2.drawString(getText(), textX, textY);

                g2.dispose();
            }
        };

        checkbox.setFont(ThemeManager.getNormalFont());
        checkbox.setPreferredSize(new Dimension(150, 30));
        checkbox.setOpaque(false);
        checkbox.setFocusPainted(false);
        checkbox.setBorderPainted(false);
        checkbox.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));

        return checkbox;
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

    private void setupEventHandlers() {
        themeToggle.setSelected(ThemeManager.isDarkMode());
        themeToggle.addActionListener(e -> {
            ThemeManager.toggleTheme();
            updateThemeLabel();

            bundle.App.applyTheme(ThemeManager.isDarkMode());

            if (themeUpdateCallback != null) {
                themeUpdateCallback.run();
            }
        });

        preserveSettingsToggle.setSelected(appSettings.isPreserveUserSettings());
        preserveSettingsToggle.addActionListener(e -> {
            boolean newValue = preserveSettingsToggle.isSelected();
            appSettings.setPreserveUserSettings(newValue);
            updatePreserveLabel();

            if (!newValue) {
                appSettings.saveSettings();
            }
        });

        resetButton.addActionListener(e -> {
            int result = JOptionPane.showConfirmDialog(
                    this,
                    "¿Estás seguro de que quieres restaurar todas las configuraciones?",
                    "Confirmar restauración",
                    JOptionPane.YES_NO_OPTION,
                    JOptionPane.QUESTION_MESSAGE
            );

            if (result == JOptionPane.YES_OPTION) {
                resetToDefaults();
            }
        });
    }

    private void resetToDefaults() {
        appSettings.resetToDefaults();

        themeToggle.setSelected(appSettings.isDarkMode());
        preserveSettingsToggle.setSelected(appSettings.isPreserveUserSettings());

        AppSettings.FolderType[] folders = AppSettings.FolderType.values();
        for (int i = 0; i < folders.length; i++) {
            folderCheckboxes[i].setSelected(appSettings.isFolderSelected(folders[i].getFolderName()));
        }

        updateThemeLabel();
        updatePreserveLabel();

        if (themeUpdateCallback != null) {
            themeUpdateCallback.run();
        }

        JOptionPane.showMessageDialog(
                this,
                "Configuraciones restauradas correctamente",
                "Restauración completa",
                JOptionPane.INFORMATION_MESSAGE
        );
    }

    private void updateThemeLabel() {
        themeLabel.setText(ThemeManager.isDarkMode() ? "Modo oscuro" : "Modo claro");
    }

    private void updatePreserveLabel() {
        preserveLabel.setText(preserveSettingsToggle.isSelected() ?
                "Guardar configuración al cerrar" :
                "No guardar configuración al cerrar");
    }

    public void updateTheme() {
        ThemeManager.Colors colors = ThemeManager.getCurrentColors();

        themeLabel.setForeground(colors.text);
        preserveLabel.setForeground(colors.text);

        updateTitledBorderColors();

        for (Component comp : getComponents()) {
            updateComponentTheme(comp, colors);
        }

        for (JCheckBox checkbox : folderCheckboxes) {
            checkbox.repaint();
        }

        resetButton.repaint();
        themeToggle.repaint();
        preserveSettingsToggle.repaint();

        updateThemeLabel();
        updatePreserveLabel();

        repaint();
    }

    private void updateTitledBorderColors() {
        ThemeManager.Colors colors = ThemeManager.getCurrentColors();

        Color titleColor = ThemeManager.isDarkMode() ?
                new Color(220, 220, 220) :
                new Color(25, 25, 25);

        updateTitledBorderColor(themePanel, titleColor);
        updateTitledBorderColor(foldersPanel, titleColor);
        updateTitledBorderColor(installerPanel, titleColor);
    }

    private void updateTitledBorderColor(JPanel panel, Color color) {
        if (panel != null && panel.getBorder() instanceof TitledBorder) {
            TitledBorder border = (TitledBorder) panel.getBorder();
            border.setTitleColor(color);
            panel.repaint();
        } else if (panel != null && panel.getBorder() instanceof javax.swing.border.CompoundBorder) {
            javax.swing.border.CompoundBorder compoundBorder =
                    (javax.swing.border.CompoundBorder) panel.getBorder();
            if (compoundBorder.getOutsideBorder() instanceof TitledBorder) {
                TitledBorder titledBorder = (TitledBorder) compoundBorder.getOutsideBorder();
                titledBorder.setTitleColor(color);
                panel.repaint();
            }
        }
    }

    private void updateComponentTheme(Component comp, ThemeManager.Colors colors) {
        if (comp instanceof JPanel) {
            JPanel panel = (JPanel) comp;
            for (Component child : panel.getComponents()) {
                updateComponentTheme(child, colors);
            }
        } else if (comp instanceof JLabel) {
            comp.setForeground(colors.text);
        }
    }
}