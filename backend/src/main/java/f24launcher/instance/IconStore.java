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
 * Guarda y normaliza el icono de una instancia.
 *
 * <p>Imágenes <b>estáticas</b> (PNG/JPEG…) se normalizan a un PNG de 256x256
 * <b>conservando la proporción</b> (se escala para caber, sin recortar) sobre un lienzo
 * transparente: no se pierde ninguna parte de la imagen ni la transparencia.</p>
 *
 * <p><b>GIF</b>: se guarda el archivo <b>tal cual</b> en {@code icon.gif} (conserva la
 * animación) y, además, un {@code icon.png} normalizado de su primer fotograma — que usan
 * el acceso directo (.ico) y cualquier consumidor que espere PNG. Al mostrar/servir el
 * icono se prefiere el {@code icon.gif} si existe. Hay un tope de tamaño para el gif.</p>
 *
 * <p>Archivos en {@code instances-data/<id>/}: {@code icon.png} (siempre tras guardar) y,
 * solo para gifs, {@code icon.gif}.</p>
 */
public final class IconStore {

    private static final Logger log = LoggerFactory.getLogger(IconStore.class);
    private static final int SIZE = 256;
    /** Tope para guardar un gif animado tal cual; por encima solo se usa su primer fotograma. */
    private static final int MAX_GIF_BYTES = 8 * 1024 * 1024;

    private IconStore() {}

    /** Guarda el icono desde un PNG/JPEG/GIF en base64 (admite el prefijo {@code data:...}). */
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

    /** Guarda el icono desde los bytes crudos de una imagen (PNG/JPEG/GIF). */
    public static boolean saveBytes(String id, byte[] raw) {
        if (raw == null || raw.length == 0) return false;
        try {
            BufferedImage src = ImageIO.read(new ByteArrayInputStream(raw)); // primer fotograma si es gif
            if (src == null) {
                log.warn("Icono de {}: los datos no son una imagen válida.", id);
                return false;
            }
            Path png = LauncherPaths.instanceIcon(id);
            Path gif = png.resolveSibling("icon.gif");
            Files.createDirectories(png.getParent());
            // Siempre: PNG normalizado (256x256, proporción conservada, fondo transparente).
            ImageIO.write(normalize(src), "png", png.toFile());
            // GIF (dentro del tope): además, el original tal cual para conservar la animación.
            if (isGif(raw) && raw.length <= MAX_GIF_BYTES) {
                Files.write(gif, raw);
            } else {
                Files.deleteIfExists(gif); // imagen estática (o gif demasiado grande) → sin gif
            }
            return true;
        } catch (Exception e) {
            log.warn("No se pudo guardar el icono de {}: {}", id, e.getMessage());
            return false;
        }
    }

    public static boolean delete(String id) {
        Path png = LauncherPaths.instanceIcon(id);
        boolean a = false, b = false;
        try { a = Files.deleteIfExists(png); } catch (Exception ignored) {}
        try { b = Files.deleteIfExists(png.resolveSibling("icon.gif")); } catch (Exception ignored) {}
        return a || b;
    }

    public static boolean exists(String id) {
        return iconFile(id) != null;
    }

    /**
     * Archivo de icono a servir/mostrar: el {@code icon.gif} animado si existe; si no, el
     * {@code icon.png}; {@code null} si la instancia no tiene icono.
     */
    public static Path iconFile(String id) {
        Path png = LauncherPaths.instanceIcon(id);
        Path gif = png.resolveSibling("icon.gif");
        if (Files.exists(gif)) return gif;
        if (Files.exists(png)) return png;
        return null;
    }

    /** ¿Los bytes son un GIF? (firma "GIF8"). */
    private static boolean isGif(byte[] b) {
        return b.length >= 4 && b[0] == 'G' && b[1] == 'I' && b[2] == 'F' && b[3] == '8';
    }

    /** Escala a 256x256 conservando proporción (sin recortar) y centra sobre lienzo transparente. */
    private static BufferedImage normalize(BufferedImage src) {
        int w = src.getWidth(), h = src.getHeight();
        double scale = Math.min((double) SIZE / w, (double) SIZE / h);
        int dw = Math.max(1, (int) Math.round(w * scale));
        int dh = Math.max(1, (int) Math.round(h * scale));
        int dx = (SIZE - dw) / 2, dy = (SIZE - dh) / 2;
        BufferedImage out = new BufferedImage(SIZE, SIZE, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = out.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.drawImage(src, dx, dy, dx + dw, dy + dh, 0, 0, w, h, null);
        g.dispose();
        return out;
    }
}
