package bundle.gui;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.HashMap;
import java.util.Map;

public class NavigationPanel extends JPanel {
    private final Map<String, JButton> buttons = new HashMap<>();
    private final Runnable modpacksCallback;
    private final Runnable loadersCallback;
    private final Runnable settingsCallback;
    private String selectedButtonKey = "MODPACKS";

    public NavigationPanel(Runnable modpacksCallback, Runnable loadersCallback, Runnable settingsCallback) {
        this.modpacksCallback = modpacksCallback;
        this.loadersCallback = loadersCallback;
        this.settingsCallback = settingsCallback;

        setLayout(new BoxLayout(this, BoxLayout.Y_AXIS));
        setBorder(new EmptyBorder(20, 15, 20, 15));
        setPreferredSize(new Dimension(200, 0));

        createButtons();
        layoutButtons();
        updateTheme();
    }

    private void createButtons() {
        buttons.put("MODPACKS", createNavButton("Modpacks", modpacksCallback, "MODPACKS"));
        buttons.put("LOADERS", createNavButton("Loaders", loadersCallback, "LOADERS"));
        buttons.put("SETTINGS", createNavButton("Configuración", settingsCallback, "SETTINGS"));
    }

    private void layoutButtons() {
        add(buttons.get("MODPACKS"));
        add(Box.createVerticalStrut(10));
        add(buttons.get("LOADERS"));
        add(Box.createVerticalStrut(10));
        add(buttons.get("SETTINGS"));
        add(Box.createVerticalGlue());
    }

    private JButton createNavButton(String text, Runnable callback, String key) {
        JButton button = new JButton(text) {
            @Override
            protected void paintComponent(Graphics g) {
                Graphics2D g2 = (Graphics2D) g.create();
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

                ThemeManager.Colors colors = ThemeManager.getCurrentColors();

                if (key.equals(selectedButtonKey)) {
                    g2.setColor(colors.primary);
                } else if (getModel().isRollover()) {
                    g2.setColor(colors.buttonHover.darker());
                } else {
                    g2.setColor(colors.panelBackground);
                }

                g2.fillRoundRect(0, 0, getWidth(), getHeight(), 12, 12);

                if (key.equals(selectedButtonKey)) {
                    g2.setColor(Color.WHITE);
                } else {
                    g2.setColor(getForeground());
                }

                g2.setFont(getFont());
                FontMetrics fm = g2.getFontMetrics();
                int textX = (getWidth() - fm.stringWidth(getText())) / 2;
                int textY = (getHeight() + fm.getAscent()) / 2 - 2;
                g2.drawString(getText(), textX, textY);

                g2.dispose();
            }
        };

        button.setFont(ThemeManager.getBoldFont());
        button.setPreferredSize(new Dimension(170, 40));
        button.setMaximumSize(new Dimension(170, 40));
        button.setAlignmentX(Component.LEFT_ALIGNMENT);
        button.setFocusPainted(false);
        button.setBorderPainted(false);
        button.setContentAreaFilled(false);
        button.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));

        button.addActionListener(e -> {
            selectButton(key);
            callback.run();
        });

        button.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseEntered(MouseEvent e) {
                button.repaint();
            }

            @Override
            public void mouseExited(MouseEvent e) {
                button.repaint();
            }
        });

        return button;
    }

    public void selectButton(String key) {
        selectedButtonKey = key;
        repaint();
    }

    public void updateTheme() {
        ThemeManager.Colors colors = ThemeManager.getCurrentColors();
        setBackground(colors.panelBackground);

        for (JButton button : buttons.values()) {
            button.setForeground(colors.text);
            button.repaint();
        }

        repaint();
    }
}