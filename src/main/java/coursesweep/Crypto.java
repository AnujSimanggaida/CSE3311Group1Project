package coursesweep;

import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;

/**
 * All cryptography used by the server, so nothing sensitive reaches the
 * database in plain text:
 *
 *   passwords       salted PBKDF2-HMAC-SHA256 (one-way; cannot be decrypted)
 *   session/reset   random tokens, stored only as SHA-256 hashes
 *   email addresses AES-256-GCM encrypted, plus a keyed HMAC used for lookup
 */
final class Crypto {

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final Base64.Encoder B64 = Base64.getEncoder();
    private static final Base64.Encoder B64_URL = Base64.getUrlEncoder().withoutPadding();

    private static final String PASSWORD_SCHEME = "pbkdf2-sha256";
    private static final int PASSWORD_ITERATIONS = 600_000;
    private static final int SALT_BYTES = 16;
    private static final int HASH_BITS = 256;

    private static final int GCM_IV_BYTES = 12;
    private static final int GCM_TAG_BITS = 128;

    private final SecretKeySpec emailIndexKey;
    private final SecretKeySpec emailEncryptionKey;

    private Crypto(byte[] masterKey) {
        // Separate sub-keys so the lookup HMAC and the encryption never share a key.
        this.emailIndexKey = new SecretKeySpec(hmac(masterKey, "email-index"), "HmacSHA256");
        this.emailEncryptionKey = new SecretKeySpec(hmac(masterKey, "email-encryption"), "AES");
    }

    /**
     * Loads the master key from COURSESWEEP_SECRET_KEY, or from keyFile,
     * generating the file on first run. Losing this key makes stored email
     * addresses unreadable and existing accounts unreachable.
     */
    static Crypto load(Path keyFile) throws IOException {
        String fromEnv = System.getenv("COURSESWEEP_SECRET_KEY");
        byte[] key;
        if (fromEnv != null && !fromEnv.isBlank()) {
            key = Base64.getDecoder().decode(fromEnv.trim());
        } else if (Files.exists(keyFile)) {
            key = Base64.getDecoder().decode(Files.readString(keyFile).trim());
        } else {
            key = randomBytes(32);
            Files.createDirectories(keyFile.toAbsolutePath().getParent());
            Files.writeString(keyFile, B64.encodeToString(key));
            try {
                Files.setPosixFilePermissions(keyFile, PosixFilePermissions.fromString("rw-------"));
            } catch (UnsupportedOperationException e) {
                // Non-POSIX file system (e.g. Windows); rely on the default ACLs.
            }
        }
        if (key.length != 32) {
            throw new IllegalStateException("The secret key must be 32 bytes, base64 encoded.");
        }
        return new Crypto(key);
    }

    // ---------- Passwords ----------

    /** Returns "pbkdf2-sha256$iterations$salt$hash" with a fresh random salt. */
    static String hashPassword(String password) {
        byte[] salt = randomBytes(SALT_BYTES);
        byte[] hash = pbkdf2(password, salt, PASSWORD_ITERATIONS);
        return PASSWORD_SCHEME + "$" + PASSWORD_ITERATIONS + "$"
                + B64.encodeToString(salt) + "$" + B64.encodeToString(hash);
    }

    static boolean verifyPassword(String password, String stored) {
        String[] parts = stored.split("\\$");
        if (parts.length != 4 || !parts[0].equals(PASSWORD_SCHEME)) {
            return false;
        }
        byte[] salt = Base64.getDecoder().decode(parts[2]);
        byte[] expected = Base64.getDecoder().decode(parts[3]);
        byte[] actual = pbkdf2(password, salt, Integer.parseInt(parts[1]));
        return MessageDigest.isEqual(expected, actual);
    }

    private static byte[] pbkdf2(String password, byte[] salt, int iterations) {
        PBEKeySpec spec = new PBEKeySpec(password.toCharArray(), salt, iterations, HASH_BITS);
        try {
            return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        } finally {
            spec.clearPassword();
        }
    }

    // ---------- Tokens ----------

    /** A 256-bit random token, safe to put in a URL or cookie. */
    static String newToken() {
        return B64_URL.encodeToString(randomBytes(32));
    }

    /** Tokens are high-entropy, so a plain SHA-256 is enough to store them safely. */
    static String hashToken(String token) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8));
            return B64.encodeToString(digest);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    // ---------- Email addresses ----------

    /** Deterministic keyed hash of an email, used to find a user without storing the address. */
    String emailIndex(String email) {
        return B64.encodeToString(hmac(emailIndexKey.getEncoded(), email));
    }

    String encrypt(String plainText) {
        try {
            byte[] iv = randomBytes(GCM_IV_BYTES);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, emailEncryptionKey, new GCMParameterSpec(GCM_TAG_BITS, iv));
            byte[] cipherText = cipher.doFinal(plainText.getBytes(StandardCharsets.UTF_8));
            byte[] out = Arrays.copyOf(iv, iv.length + cipherText.length);
            System.arraycopy(cipherText, 0, out, iv.length, cipherText.length);
            return B64.encodeToString(out);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    String decrypt(String encoded) {
        try {
            byte[] in = Base64.getDecoder().decode(encoded);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, emailEncryptionKey,
                    new GCMParameterSpec(GCM_TAG_BITS, in, 0, GCM_IV_BYTES));
            byte[] plain = cipher.doFinal(in, GCM_IV_BYTES, in.length - GCM_IV_BYTES);
            return new String(plain, StandardCharsets.UTF_8);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Could not decrypt stored value; is the secret key correct?", e);
        }
    }

    // ---------- Helpers ----------

    private static byte[] randomBytes(int count) {
        byte[] bytes = new byte[count];
        RANDOM.nextBytes(bytes);
        return bytes;
    }

    private static byte[] hmac(byte[] key, String message) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            return mac.doFinal(message.getBytes(StandardCharsets.UTF_8));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }
}
