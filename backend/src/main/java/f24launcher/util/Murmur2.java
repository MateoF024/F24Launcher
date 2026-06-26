package f24launcher.util;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Fingerprint de CurseForge: MurmurHash2 de 32 bits (semilla 1) calculado sobre el
 * archivo tras eliminar los bytes de espacio en blanco que CurseForge ignora
 * (tab=9, LF=10, CR=13, espacio=32). Es la forma en que CurseForge identifica
 * archivos de forma exacta.
 *
 * <p>Se calcula <b>sin alojar una copia normalizada del archivo</b> (la versión
 * anterior reservaba {@code new byte[data.length]}, duplicando la memoria por
 * archivo y provocando OutOfMemoryError con muchos mods grandes). MurmurHash2
 * necesita la longitud normalizada antes de empezar, así que sobre un archivo se
 * hacen dos pasadas en streaming (buffer fijo, memoria constante): una cuenta los
 * bytes no-espacio y otra alimenta el hash.</p>
 */
public final class Murmur2 {

    private static final int BUF = 1 << 16; // 64 KB

    private Murmur2() {}

    /** Fingerprint CF de unos bytes en memoria, sin copiar el buffer normalizado. */
    public static long curseForgeFingerprint(byte[] data) {
        int len = 0;
        for (byte b : data) if (!isWhitespace(b)) len++;
        Hasher h = new Hasher(len);
        for (byte b : data) if (!isWhitespace(b)) h.update(b);
        return h.finish();
    }

    /**
     * Fingerprint CF de un archivo por streaming (memoria constante, dos pasadas).
     * Si ya se conoce la longitud normalizada, usar {@link #curseForgeFingerprint(Path, int)}
     * para ahorrar la primera pasada.
     */
    public static long curseForgeFingerprint(Path file) throws IOException {
        return curseForgeFingerprint(file, normalizedLength(file));
    }

    /** Número de bytes no-espacio del archivo (la longitud que usa la semilla del hash). */
    public static int normalizedLength(Path file) throws IOException {
        long len = 0;
        byte[] buf = new byte[BUF];
        try (InputStream in = new BufferedInputStream(Files.newInputStream(file), BUF)) {
            int n;
            while ((n = in.read(buf)) > 0)
                for (int i = 0; i < n; i++) if (!isWhitespace(buf[i])) len++;
        }
        return (int) len;
    }

    /** Segunda pasada: hash del archivo conocida ya su longitud normalizada. */
    public static long curseForgeFingerprint(Path file, int normalizedLen) throws IOException {
        Hasher h = new Hasher(normalizedLen);
        byte[] buf = new byte[BUF];
        try (InputStream in = new BufferedInputStream(Files.newInputStream(file), BUF)) {
            int n;
            while ((n = in.read(buf)) > 0)
                for (int i = 0; i < n; i++) if (!isWhitespace(buf[i])) h.update(buf[i]);
        }
        return h.finish();
    }

    private static boolean isWhitespace(byte b) {
        return b == 9 || b == 10 || b == 13 || b == 32;
    }

    /**
     * Estado incremental de MurmurHash2 (32 bits, semilla 1) sobre bytes ya
     * normalizados. Acumula de 4 en 4 bytes igual que el algoritmo original.
     */
    private static final class Hasher {
        private static final long M = 0x5bd1e995L;
        private static final int R = 24;

        private long h;
        private final int[] block = new int[4];
        private int fill = 0;

        Hasher(int length) {
            this.h = (1L ^ length) & 0xFFFFFFFFL; // semilla = 1
        }

        void update(byte b) {
            block[fill++] = b & 0xFF;
            if (fill == 4) {
                long k = block[0] | (block[1] << 8) | (block[2] << 16) | ((long) block[3] << 24);
                k = (k * M) & 0xFFFFFFFFL;
                k ^= (k >>> R);
                k = (k * M) & 0xFFFFFFFFL;
                h = (h * M) & 0xFFFFFFFFL;
                h ^= k;
                fill = 0;
            }
        }

        long finish() {
            if (fill >= 3) h ^= (long) block[2] << 16;
            if (fill >= 2) h ^= (long) block[1] << 8;
            if (fill >= 1) {
                h ^= block[0];
                h = (h * M) & 0xFFFFFFFFL;
            }
            h ^= (h >>> 13);
            h = (h * M) & 0xFFFFFFFFL;
            h ^= (h >>> 15);
            return h & 0xFFFFFFFFL;
        }
    }
}
