package in.aitradingsystem.provider.angelone;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.security.GeneralSecurityException;
import java.time.Instant;
import java.util.Locale;

/**
 * RFC 6238 TOTP (HMAC-SHA1, 30-second step, 6 digits) used for the SmartAPI login step.
 */
final class AngelOneTotp {

    private static final String BASE32 = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567";
    private static final long STEP_SECONDS = 30;

    private AngelOneTotp() {
    }

    static String generate(String base32Secret, Instant at) {
        byte[] key = base32Decode(base32Secret);
        long counter = at.getEpochSecond() / STEP_SECONDS;
        try {
            Mac mac = Mac.getInstance("HmacSHA1");
            mac.init(new SecretKeySpec(key, "HmacSHA1"));
            byte[] hash = mac.doFinal(ByteBuffer.allocate(8).putLong(counter).array());
            int offset = hash[hash.length - 1] & 0x0f;
            int binary = ((hash[offset] & 0x7f) << 24)
                    | ((hash[offset + 1] & 0xff) << 16)
                    | ((hash[offset + 2] & 0xff) << 8)
                    | (hash[offset + 3] & 0xff);
            return String.format(Locale.ROOT, "%06d", binary % 1_000_000);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HmacSHA1 unavailable", e);
        }
    }

    static byte[] base32Decode(String input) {
        String clean = input.replace(" ", "").replace("-", "").toUpperCase(Locale.ROOT).replaceAll("=+$", "");
        if (clean.isEmpty()) {
            throw new IllegalArgumentException("TOTP secret is empty");
        }
        byte[] out = new byte[clean.length() * 5 / 8];
        int buffer = 0;
        int bitsLeft = 0;
        int index = 0;
        for (char c : clean.toCharArray()) {
            int val = BASE32.indexOf(c);
            if (val < 0) {
                throw new IllegalArgumentException("TOTP secret is not valid base32");
            }
            buffer = ((buffer << 5) | val) & 0xFFFF;
            bitsLeft += 5;
            if (bitsLeft >= 8) {
                out[index++] = (byte) (buffer >> (bitsLeft - 8));
                bitsLeft -= 8;
            }
        }
        return out;
    }
}
