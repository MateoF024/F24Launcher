package bundle.gui;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;

/**
 * Fase 5 — Fábrica centralizada de componentes GUI reutilizables.
 * Elimina la duplicación entre ModpacksPanel y LoadersPanel.
 */
public final class GuiFactory {

    private GuiFactory() { }

    public static JComboBox<String> createStyledComboBox(String[] items) {
        JComboBox<String> combo = new JComboBox<>(items);
        combo.setFont(ThemeManager.getNormalFont());
        combo.setPreferredSize(new Dimension(280, 36));
        combo.setRenderer(new DefaultListCellRenderer() {
            @Override
            public Component getListCellRendererComponent(JList<?> list, Object value, int index,
                                                          boolean isSelected, boolean cellHasFocus) {
                JLabel label = (JLabel) super.getListCellRendererComponent(
                        list, value, index, isSelected, cellHasFocus);
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

    public static <T> JComboBox<T> createStyledGenericComboBox(T[] items) {
        JComboBox<T> combo = new JComboBox<>(items);
        combo.setFont(ThemeManager.getNormalFont());
        combo.setPreferredSize(new Dimension(280, 36));
        combo.setRenderer(new DefaultListCellRenderer() {
            @Override
            public Component getListCellRendererComponent(JList<?> list, Object value, int index,
                                                          boolean isSelected, boolean cellHasFocus) {
                JLabel label = (JLabel) super.getListCellRendererComponent(
                        list, value, index, isSelected, cellHasFocus);
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

    public static JTextField createStyledTextField(String text) {
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

    public static JButton createStyledButton(String text, int width, int height) {
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

    public static JProgressBar createStyledProgressBar() {
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

    /**
     * Crea un panel de progreso estándar con status, barra y porcentaje.
     * Retorna un contenedor con los tres componentes ya referenciables.
     */
    public static ProgressPanelComponents createProgressPanel() {
        JPanel panel = new JPanel(new GridBagLayout());
        panel.setOpaque(false);
        panel.setBorder(new EmptyBorder(40, 40, 40, 40));

        JPanel content = new JPanel();
        content.setLayout(new BoxLayout(content, BoxLayout.Y_AXIS));
        content.setOpaque(false);

        JLabel statusLabel = new JLabel("Preparando instalación...", SwingConstants.CENTER);
        statusLabel.setFont(ThemeManager.getNormalFont());
        statusLabel.setAlignmentX(Component.CENTER_ALIGNMENT);
        content.add(statusLabel);
        content.add(Box.createVerticalStrut(20));

        JProgressBar progressBar = createStyledProgressBar();
        progressBar.setAlignmentX(Component.CENTER_ALIGNMENT);
        content.add(progressBar);
        content.add(Box.createVerticalStrut(12));

        JLabel progressLabel = new JLabel("0%", SwingConstants.CENTER);
        progressLabel.setFont(ThemeManager.getSmallFont());
        progressLabel.setAlignmentX(Component.CENTER_ALIGNMENT);
        content.add(progressLabel);

        GridBagConstraints gbc = new GridBagConstraints();
        gbc.gridx = 0; gbc.gridy = 0;
        gbc.weightx = 1.0; gbc.weighty = 1.0;
        gbc.anchor = GridBagConstraints.CENTER;
        panel.add(content, gbc);

        return new ProgressPanelComponents(panel, statusLabel, progressBar, progressLabel);
    }

    /**
     * Crea un panel de resultado estándar con label y botón volver.
     */
    public static ResultPanelComponents createResultPanel(Runnable onBack) {
        JPanel panel = new JPanel(new GridBagLayout());
        panel.setOpaque(false);
        panel.setBorder(new EmptyBorder(40, 40, 40, 40));

        JPanel content = new JPanel();
        content.setLayout(new BoxLayout(content, BoxLayout.Y_AXIS));
        content.setOpaque(false);

        JLabel resultLabel = new JLabel("", SwingConstants.CENTER);
        resultLabel.setFont(ThemeManager.getBoldFont());
        resultLabel.setAlignmentX(Component.CENTER_ALIGNMENT);
        content.add(resultLabel);
        content.add(Box.createVerticalStrut(25));

        JButton backButton = createStyledButton("Volver", 120, 40);
        backButton.setAlignmentX(Component.CENTER_ALIGNMENT);
        backButton.addActionListener(e -> onBack.run());
        content.add(backButton);

        GridBagConstraints gbc = new GridBagConstraints();
        gbc.gridx = 0; gbc.gridy = 0;
        gbc.weightx = 1.0; gbc.weighty = 1.0;
        gbc.anchor = GridBagConstraints.CENTER;
        panel.add(content, gbc);

        return new ResultPanelComponents(panel, resultLabel, backButton);
    }

    // --- Contenedores de componentes ---

    public static class ProgressPanelComponents {
        public final JPanel panel;
        public final JLabel statusLabel;
        public final JProgressBar progressBar;
        public final JLabel progressLabel;

        ProgressPanelComponents(JPanel panel, JLabel statusLabel,
                                JProgressBar progressBar, JLabel progressLabel) {
            this.panel = panel;
            this.statusLabel = statusLabel;
            this.progressBar = progressBar;
            this.progressLabel = progressLabel;
        }

        public void reset() {
            progressBar.setValue(0);
            progressLabel.setText("0%");
            statusLabel.setText("Preparando instalación...");
        }
    }

    public static class ResultPanelComponents {
        public final JPanel panel;
        public final JLabel resultLabel;
        public final JButton backButton;

        ResultPanelComponents(JPanel panel, JLabel resultLabel, JButton backButton) {
            this.panel = panel;
            this.resultLabel = resultLabel;
            this.backButton = backButton;
        }

        public void show(String message, Color color) {
            String html = "<html><div style='text-align: center; width: 400px;'>"
                    + message.replace("\n", "<br>") + "</div></html>";
            resultLabel.setText(html);
            resultLabel.setForeground(color);
        }
    }
}