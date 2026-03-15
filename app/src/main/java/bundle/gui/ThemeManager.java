package bundle.gui;

import bundle.settings.AppSettings;
import java.awt.*;
import java.awt.GraphicsEnvironment;
import java.util.Set;

public class ThemeManager {

    // #13 — Fuentes cacheadas como constantes

    private static final Font FONT_NORMAL;
    private static final Font FONT_BOLD;
    private static final Font FONT_SMALL;
    private static final Font FONT_LARGE;

    static {
        GraphicsEnvironment ge = GraphicsEnvironment.getLocalGraphicsEnvironment();
        Set<String> fonts = new java.util.HashSet<>(java.util.Arrays.asList(ge.getAvailableFontFamilyNames()));
        String preferred = fonts.contains("Segoe UI") ? "Segoe UI" : Font.DIALOG;
        FONT_NORMAL = buildFont(preferred, Font.PLAIN,  13);
        FONT_BOLD   = buildFont(preferred, Font.BOLD,   13);
        FONT_SMALL  = buildFont(preferred, Font.PLAIN,  11);
        FONT_LARGE  = buildFont(preferred, Font.BOLD,   15);
    }

    private static Font buildFont(String family, int style, int size) {
        if (family.equals(Font.DIALOG)) return new Font(Font.DIALOG, style, size);
        java.util.Map<java.awt.font.TextAttribute, Object> attrs = new java.util.HashMap<>();
        attrs.put(java.awt.font.TextAttribute.FAMILY, family);
        attrs.put(java.awt.font.TextAttribute.WEIGHT,
                style == Font.BOLD ? java.awt.font.TextAttribute.WEIGHT_BOLD
                        : java.awt.font.TextAttribute.WEIGHT_REGULAR);
        attrs.put(java.awt.font.TextAttribute.SIZE, (float) size);
        return Font.getFont(attrs);
    }

    // #14 — Estado de tema cacheado, evita llamar AppSettings en cada repaint
    private static boolean darkMode = AppSettings.getInstance().isDarkMode();

    public static class Colors {
        public final Color background, titleBar, text, primary, fieldBackground;
        public final Color success, progressBackground, border, buttonHover, panelBackground;

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
            new Color(18, 18, 18),
            new Color(28, 28, 28),
            new Color(240, 240, 240),
            new Color(30, 136, 229),
            new Color(42, 42, 42),
            new Color(76, 175, 80),
            new Color(35, 35, 35),
            new Color(65, 65, 65),
            new Color(50, 146, 239),
            new Color(22, 22, 22)
    );

    public static final Colors LIGHT = new Colors(
            new Color(252, 252, 252),
            new Color(225, 225, 225),
            new Color(25, 25, 25),
            new Color(13, 110, 253),
            new Color(255, 255, 255),
            new Color(25, 135, 84),
            new Color(200, 200, 200),
            new Color(160, 160, 160),
            new Color(0, 86, 179),
            new Color(240, 240, 240)
    );

    public static boolean isDarkMode() {
        return darkMode;
    }

    public static Colors getCurrentColors() {
        return darkMode ? DARK : LIGHT;
    }

    // #14 — Al cambiar tema, actualizar caché local y persistir
    public static void toggleTheme() {
        darkMode = !darkMode;
        AppSettings.getInstance().setDarkMode(darkMode);
    }

    public static Font getNormalFont() { return FONT_NORMAL; }
    public static Font getBoldFont()   { return FONT_BOLD;   }
    public static Font getSmallFont()  { return FONT_SMALL;  }
    public static Font getLargeFont()  { return FONT_LARGE;  }
}