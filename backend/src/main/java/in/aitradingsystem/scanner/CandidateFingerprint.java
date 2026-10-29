package in.aitradingsystem.scanner;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;

/** Deterministic SHA-256 identity for research candidates. */
public final class CandidateFingerprint {
    private CandidateFingerprint() {}

    public static String of(long instrumentId, String interval, String strategy, String strategyVersion,
                            Instant signalCandleTime, BigDecimal entry, BigDecimal stop, BigDecimal target) {
        if (instrumentId <= 0) throw new IllegalArgumentException("instrumentId must be positive");
        String canonical = instrumentId + "|" + required(interval, "interval") + "|"
                + required(strategy, "strategy") + "|" + required(strategyVersion, "strategyVersion") + "|"
                + required(signalCandleTime, "signalCandleTime").toString() + "|"
                + decimal(entry, "entry") + "|" + decimal(stop, "stop") + "|" + decimal(target, "target");
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
    }

    static String decimal(BigDecimal value, String field) {
        if (value == null) throw new IllegalArgumentException(field + " is required");
        return value.stripTrailingZeros().toPlainString();
    }

    private static String required(String value, String field) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " is required");
        return value.trim();
    }

    private static Instant required(Instant value, String field) {
        if (value == null) throw new IllegalArgumentException(field + " is required");
        return value;
    }
}
