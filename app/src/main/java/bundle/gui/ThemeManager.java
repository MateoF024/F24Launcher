package bundle.gui;

import bundle.settings.AppSettings;

import java.awt.*;

public class ThemeManager {
    private static AppSettings appSettings = AppSettings.getInstance();

    public static class Colors {
        public final Color background;
        public final Color titleBar;
        public final Color text;
        public final Color primary;
        public final Color fieldBackground;
        public final Color success;
        public final Color progressBackground;
        public final Color border;
        public final Color buttonHover;
        public final Color panelBackground;

        private Colors(Color bg, Color title, Color text, Color primary, Color fieldBg,
                       Color success, Color progBg, Color border, Color hover, Color panelBg) {
            this.background = bg;
            this.titleBar = title;
            this.text = text;
            this.primary = primary;
            this.fieldBackground = fieldBg;
            this.success = success;
            this.progressBackground = progBg;
            this.border = border;
            this.buttonHover = hover;
            this.panelBackground = panelBg;
        }
    }

    public static final Colors DARK = new Colors(
            new Color(18, 18, 18),    // Background - muy oscuro
            new Color(28, 28, 28),    // TitleBar - gris oscuro diferenciado
            new Color(240, 240, 240), // Text - casi blanco
            new Color(30, 136, 229),  // Primary - azul
            new Color(42, 42, 42),    // FieldBackground - gris medio oscuro
            new Color(76, 175, 80),   // Success - verde
            new Color(35, 35, 35),    // ProgressBackground - gris oscuro
            new Color(65, 65, 65),    // Border - gris medio
            new Color(50, 146, 239),  // ButtonHover - azul más claro
            new Color(22, 22, 22)     // PanelBackground - gris muy oscuro
    );

    public static final Colors LIGHT = new Colors(
            new Color(252, 252, 252), // Background - casi blanco
            new Color(225, 225, 225), // TitleBar - gris claro diferenciado
            new Color(25, 25, 25),    // Text - casi negro
            new Color(13, 110, 253),  // Primary - azul
            new Color(255, 255, 255), // FieldBackground - blanco puro
            new Color(25, 135, 84),   // Success - verde
            new Color(200, 200, 200), // ProgressBackground - gris medio
            new Color(160, 160, 160), // Border - gris medio oscuro
            new Color(0, 86, 179),    // ButtonHover - azul oscuro
            new Color(240, 240, 240)  // PanelBackground - gris claro
    );

    public static boolean isDarkMode() {
        return appSettings.isDarkMode();
    }

    public static Colors getCurrentColors() {
        return isDarkMode() ? DARK : LIGHT;
    }

    public static void toggleTheme() {
        appSettings.setDarkMode(!appSettings.isDarkMode());
    }

    public static Font getNormalFont() {
        return new Font("Segoe UI", Font.PLAIN, 13);
    }

    public static Font getBoldFont() {
        return new Font("Segoe UI", Font.BOLD, 13);
    }

    public static Font getSmallFont() {
        return new Font("Segoe UI", Font.PLAIN, 11);
    }

    public static Font getLargeFont() {
        return new Font("Segoe UI", Font.BOLD, 15);
    }
}