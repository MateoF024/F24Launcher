package f24launcher.instance;

import f24launcher.core.LauncherPaths;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.imageio.ImageIO;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;

/**
 * Guarda y normaliza el icono de una instancia. Acepta un PNG/JPEG/GIF en base64
 * (admite el prefijo {@code data:image/...;base64,}), lo recorta a cuadrado
 * centrado y lo escribe como PNG de 256x256 en {@code instances-data/<id>/icon.png}.
 */
public final class IconStore {

    private static final Logger log = LoggerFactory.getLogger(IconStore.class);
    private static final int SIZE = 256;

    private IconStore() {}

    /** Guarda el icono normalizado desde un PNG/JPEG/GIF en base64 (admite data-URL). */
    public static boolean save(String id, String base64) {
        if (base64 == null || base64.isBlank()) return false;
        try {
            int comma = base64.indexOf(',');
            String b64 = (base64.startsWith("data:") && comma >= 0) ? base64.substring(comma + 1) : base64;
            return saveBytes(id, Base64.getDecoder().decode(b64.trim()));
        } catch (Exception e) {
            log.warn("No se pudo guardar el icono de {}: {}", id, e.getMessage());
            return false;
        }
    }

    /** Guarda el icono normalizado desde los bytes crudos de una imagen. */
    public static boolean saveBytes(String id, byte[] raw) {
        if (raw == null || raw.length == 0) return false;
        try {
            BufferedImage src = ImageIO.read(new ByteArrayInputStream(raw));
            if (src == null) {
                log.warn("Icono de {}: los datos no son una imagen válida.", id);
                return false;
            }
            Path dest = LauncherPaths.instanceIcon(id);
            Files.createDirectories(dest.getParent());
            ImageIO.write(normalize(src), "png", dest.toFile());
            return true;
        } catch (Exception e) {
            log.warn("No se pudo guardar el icono de {}: {}", id, e.getMessage());
            return false;
        }
    }

    public static boolean delete(String id) {
        try {
            return Files.deleteIfExists(LauncherPaths.instanceIcon(id));
        } catch (Exception e) {
            return false;
        }
    }

    public static boolean exists(String id) {
        return Files.exists(LauncherPaths.instanceIcon(id));
    }

    /** Recorta al cuadrado central y reescala a 256x256 (ARGB, suavizado). */
    private static BufferedImage normalize(BufferedImage src) {
        int w = src.getWidth(), h = src.getHeight();
        int side = Math.min(w, h);
        int sx = (w - side) / 2, sy = (h - side) / 2;
        BufferedImage out = new BufferedImage(SIZE, SIZE, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = out.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.drawImage(src, 0, 0, SIZE, SIZE, sx, sy, sx + side, sy + side, null);
        g.dispose();
        return out;
    }
}
