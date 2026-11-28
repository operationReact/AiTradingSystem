package in.aitradingsystem.tradingrooms;

import java.security.SecureRandom;
import java.util.Base64;

/**
 * Generates and validates the unique per-order broker correlation tag that is
 * persisted with a LIVE equity claim before any broker POST. The tag is the
 * only link the platform controls between its durable claim and the broker's
 * own order book, so it must fit both broker transports' tag grammar
 * ({@code [A-Za-z0-9_-]{8,20}}) and be practically unique per broker.
 */
public final class LiveEquityCorrelationTag {
    // "MT" marks a MintTick-originated order in the broker's tag column.
    private static final String PREFIX = "MT";
    // 12 base64url chars encode 9 random bytes (72 bits); with the prefix the
    // tag is 14 chars, safely inside the 8..20 broker grammar.
    private static final int RANDOM_BYTES = 9;
    private static final java.util.regex.Pattern VALID =
        java.util.regex.Pattern.compile("[A-Za-z0-9_-]{8,20}");
    private static final SecureRandom RANDOM = new SecureRandom();

    private LiveEquityCorrelationTag() {}

    public static String generate() {
        byte[] random = new byte[RANDOM_BYTES];
        RANDOM.nextBytes(random);
        String tag = PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(random);
        if (!isValid(tag)) {
            // Defensive: encoder output is always within grammar for 9 bytes.
            throw new IllegalStateException("Generated correlation tag is invalid");
        }
        return tag;
    }

    public static boolean isValid(String tag) {
        return tag != null && VALID.matcher(tag).matches();
    }
}
