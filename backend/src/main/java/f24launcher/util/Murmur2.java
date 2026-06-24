package f24launcher.util;

/**
 * Fingerprint de CurseForge: MurmurHash2 de 32 bits (semilla 1) calculado sobre el
 * archivo tras eliminar los bytes de espacio en blanco que CurseForge ignora
 * (tab=9, LF=10, CR=13, espacio=32). Es la forma en que CurseForge identifica
 * archivos de forma exacta.
 */
public final class Murmur2 {

    private Murmur2() {}

    public static long curseForgeFingerprint(byte[] data) {
        byte[] norm = new byte[data.length];
        int len = 0;
        for (byte b : data) {
            if (b != 9 && b != 10 && b != 13 && b != 32) norm[len++] = b;
        }
        return hash(norm, len, 1L);
    }

    private static long hash(byte[] data, int length, long seed) {
        final long m = 0x5bd1e995L;
        final int r = 24;
        long h = (seed ^ length) & 0xFFFFFFFFL;
        int len = length, i = 0;
        while (len >= 4) {
            long k = (data[i] & 0xFFL) | ((data[i + 1] & 0xFFL) << 8)
                    | ((data[i + 2] & 0xFFL) << 16) | ((data[i + 3] & 0xFFL) << 24);
            k = (k * m) & 0xFFFFFFFFL;
            k ^= (k >>> r);
            k = (k * m) & 0xFFFFFFFFL;
            h = (h * m) & 0xFFFFFFFFL;
            h ^= k;
            i += 4;
            len -= 4;
        }
        if (len >= 3) h ^= (data[i + 2] & 0xFFL) << 16;
        if (len >= 2) h ^= (data[i + 1] & 0xFFL) << 8;
        if (len >= 1) {
            h ^= (data[i] & 0xFFL);
            h = (h * m) & 0xFFFFFFFFL;
        }
        h ^= (h >>> 13);
        h = (h * m) & 0xFFFFFFFFL;
        h ^= (h >>> 15);
        return h & 0xFFFFFFFFL;
    }
}
