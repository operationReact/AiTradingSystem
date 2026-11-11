package in.aitradingsystem.provider.upstox;

import java.nio.ByteBuffer;
import java.security.GeneralSecurityException;
import java.time.Instant;
import java.util.Locale;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/** RFC 6238, 30-second, six-digit TOTP for owner-only individual Upstox apps. */
final class UpstoxTotp {
    private static final String BASE32 = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567";

    private UpstoxTotp() {}

    static String generate(String secret, Instant when) {
        if (secret == null || when == null) throw new IllegalArgumentException("TOTP unavailable");
        String clean = secret.replace(" ", "").replace("-", "").toUpperCase(Locale.ROOT)
                .replaceAll("=+$", "");
        if (clean.length() < 16 || clean.length() > 256) {
            throw new IllegalArgumentException("Invalid TOTP key size");
        }
        byte[] key = new byte[clean.length() * 5 / 8];
        int buffer = 0;
        int bits = 0;
        int size = 0;
        for (char ch : clean.toCharArray()) {
            int digit = BASE32.indexOf(ch);
            if (digit == -1) throw new IllegalArgumentException("Invalid TOTP key alphabet");
            buffer = (buffer << 5) | digit;
            bits += 5;
            if (bits >= 8) {
                key[size++] = (byte) (buffer >> (bits - 8));
                bits -= 8;
            }
            buffer &= (1 << bits) - 1;
        }
        if (size < 10) throw new IllegalArgumentException("Insufficient key entropy");
        try {
            Mac mac = Mac.getInstance("HmacSHA1");
            mac.init(new SecretKeySpec(key, 0, size, "HmacSHA1"));
            byte[] hash = mac.doFinal(ByteBuffer.allocate(8)
                    .putLong(when.getEpochSecond() / 30).array());
            int offset = hash[hash.length - 1] & 0x0f;
            int code = ((hash[offset] & 0x7f) << 24)
                | ((hash[offset+1] & 0xff) << 16)
                | ((hash[offset+2] & 0xff) << 8)
                | (hash[offset+3] & 0xff);
            return String.format(Locale.ROOT, "%06d", code % 1_000_000);
        } catch (GeneralSecurityException ex) {
            throw new IllegalStateException("TOTP primitive unavailable");
        } finally {
            java.util.Arrays.fill(key, (byte) 0);
        }
    }
}
