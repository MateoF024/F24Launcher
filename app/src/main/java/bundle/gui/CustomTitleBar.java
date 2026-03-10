package bundle.gui;

import javax.swing.*;
import java.awt.*;
import java.awt.event.*;

public class CustomTitleBar extends JPanel {
    private final JFrame parentFrame;
    private final Point dragStart = new Point();
    private final JLabel titleLabel;
    private JButton btnMin;
    private JButton btnMax;
    private JButton btnClose;

    public CustomTitleBar(JFrame frame, String title) {
        this.parentFrame = frame;
        setLayout(new BorderLayout());
        setPreferredSize(new Dimension(frame.getWidth(), 36));

        JPanel left = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 6));
        left.setOpaque(false);

        titleLabel = new JLabel("F24 Installer");
        titleLabel.setFont(ThemeManager.getBoldFont());
        left.add(titleLabel);

        JPanel right = new JPanel(new FlowLayout(FlowLayout.RIGHT, 0, 4));
        right.setOpaque(false);

        btnMin = createTitleButton("—");
        btnMax = createTitleButton("□");
        btnClose = createTitleButton("✕");

        btnMin.addActionListener(e -> frame.setState(Frame.ICONIFIED));

        btnMax.setEnabled(false);
        btnMax.setForeground(new Color(100, 100, 100));
        btnMax.setCursor(Cursor.getPredefinedCursor(Cursor.DEFAULT_CURSOR));

        btnClose.addActionListener(e -> {
            frame.dispatchEvent(new WindowEvent(frame, WindowEvent.WINDOW_CLOSING));
        });

        right.add(btnMin);
        right.add(btnMax);
        right.add(btnClose);

        MouseAdapter dragAdapter = new MouseAdapter() {
            @Override
            public void mousePressed(MouseEvent e) {
                dragStart.x = e.getX();
                dragStart.y = e.getY();
            }

            @Override
            public void mouseDragged(MouseEvent e) {
                Point p = frame.getLocation();
                frame.setLocation(p.x + e.getX() - dragStart.x, p.y + e.getY() - dragStart.y);
            }
        };

        addMouseListener(dragAdapter);
        addMouseMotionListener(dragAdapter);
        titleLabel.addMouseListener(dragAdapter);
        titleLabel.addMouseMotionListener(dragAdapter);
        add(left, BorderLayout.WEST);
        add(right, BorderLayout.EAST);
        updateTheme();
    }

    private JButton createTitleButton(String text) {
        JButton button = new JButton(text);
        button.setFont(new Font("Dialog", Font.PLAIN, 12));
        button.setBorder(BorderFactory.createEmptyBorder(4, 10, 4, 10));
        button.setFocusPainted(false);
        button.setOpaque(false);
        button.setContentAreaFilled(false);
        button.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        button.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseEntered(MouseEvent e) {
                if (button.isEnabled()) {
                    button.setOpaque(true);
                    button.setBackground(new Color(80, 80, 80));
                }
            }

            @Override
            public void mouseExited(MouseEvent e) {
                button.setOpaque(false);
            }
        });

        return button;
    }

    public void updateTheme() {
        ThemeManager.Colors colors = ThemeManager.getCurrentColors();
        setBackground(colors.titleBar);

        if (titleLabel != null) {
            titleLabel.setForeground(colors.text);
        }

        if (btnMin != null) btnMin.setForeground(colors.text);
        if (btnClose != null) btnClose.setForeground(colors.text);

        repaint();
    }
}