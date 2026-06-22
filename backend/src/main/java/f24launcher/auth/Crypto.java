package f24launcher.auth;

import f24launcher.core.LauncherPaths;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * Cifrado simétrico AES-GCM para los secretos de cuenta (refresh/access tokens)
 * en reposo. La clave de 256 bits se genera una vez y se guarda en
 * <code>%APPDATA%\F24Launcher\.authkey</code>. Es protección frente a lectura
 * casual del JSON, no un secreto de grado servidor (el launcher es local).
 */
public final class Crypto {

    private Crypto() {}

    private static final int IV_LEN = 12;
    private static final int TAG_BITS = 128;
    private static final SecureRandom RNG = new SecureRandom();
    private static volatile SecretKey key;

    private static SecretKey key() throws Exception {
        if (key != null) return key;
        synchronized (Crypto.class) {
            if (key != null) return key;
            Path keyFile = LauncherPaths.root().resolve(".authkey");
            if (Files.exists(keyFile) && Files.size(keyFile) > 0) {
                byte[] raw = Base64.getDecoder().decode(Files.readString(keyFile).trim());
                key = new SecretKeySpec(raw, "AES");
            } else {
                KeyGenerator kg = KeyGenerator.getInstance("AES");
                kg.init(256);
                SecretKey k = kg.generateKey();
                Files.writeString(keyFile, Base64.getEncoder().encodeToString(k.getEncoded()));
                key = k;
            }
            return key;
        }
    }

    /** Cifra texto plano → base64(iv|ciphertext). Devuelve null si la entrada es null. */
    public static String encrypt(String plain) {
        if (plain == null) return null;
        try {
            byte[] iv = new byte[IV_LEN];
            RNG.nextBytes(iv);
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.ENCRYPT_MODE, key(), new GCMParameterSpec(TAG_BITS, iv));
            byte[] ct = c.doFinal(plain.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            byte[] out = new byte[iv.length + ct.length];
            System.arraycopy(iv, 0, out, 0, iv.length);
            System.arraycopy(ct, 0, out, iv.length, ct.length);
            return Base64.getEncoder().encodeToString(out);
        } catch (Exception e) {
            throw new RuntimeException("No se pudo cifrar el secreto", e);
        }
    }

    /** Descifra base64(iv|ciphertext) → texto plano. Devuelve null si la entrada es null. */
    public static String decrypt(String enc) {
        if (enc == null) return null;
        try {
            byte[] all = Base64.getDecoder().decode(enc);
            byte[] iv = new byte[IV_LEN];
            System.arraycopy(all, 0, iv, 0, IV_LEN);
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.DECRYPT_MODE, key(), new GCMParameterSpec(TAG_BITS, iv));
            byte[] pt = c.doFinal(all, IV_LEN, all.length - IV_LEN);
            return new String(pt, java.nio.charset.StandardCharsets.UTF_8);
        } catch (Exception e) {
            return null; // clave rotada o dato corrupto → tratar como ausente
        }
    }
}
