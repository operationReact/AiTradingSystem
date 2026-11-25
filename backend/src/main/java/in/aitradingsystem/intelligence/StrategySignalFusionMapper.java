package in.aitradingsystem.intelligence;

import in.aitradingsystem.strategy.StrategySignal;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;

/** Converts the existing Java deterministic strategy candidate into fusion evidence only. */
public final class StrategySignalFusionMapper {

    private StrategySignalFusionMapper() {}

    public static FusionRankRequest.FusionEvidence toEvidence(StrategySignal signal) {
        Objects.requireNonNull(signal, "signal");
        Instant observedAt = Objects.requireNonNull(signal.createdAt(), "signal.createdAt");
        double confidence = Math.clamp(signal.confidence(), 0.0, 1.0);
        double score = "BUY".equals(signal.action()) ? confidence : 0.0;
        return new FusionRankRequest.FusionEvidence(
                "DETERMINISTIC",
                "java-strategy",
                signal.strategy(),
                score,
                confidence,
                observedAt,
                fingerprint(signal),
                signal.reasonCodes());
    }

    static String fingerprint(StrategySignal signal) {
        String canonical = String.join("|",
                safe(signal.id()),
                Long.toString(signal.instrumentId()),
                safe(signal.symbol()),
                safe(signal.exchange()),
                safe(signal.interval()),
                safe(signal.action()),
                safe(signal.strategy()),
                decimal(signal.referencePrice()),
                decimal(signal.stopLoss()),
                decimal(signal.target()),
                decimal(signal.fastAverage()),
                decimal(signal.slowAverage()),
                decimal(signal.averageVolume()),
                decimal(signal.latestVolume()),
                Double.toString(signal.confidence()),
                String.join(",", signal.reasonCodes() == null ? List.of() : signal.reasonCodes()),
                safe(signal.source()),
                instant(signal.createdAt()),
                instant(signal.latestCandleAt()),
                safe(signal.status()));
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is required by the JVM", exception);
        }
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }

    private static String decimal(BigDecimal value) {
        return value == null ? "" : value.stripTrailingZeros().toPlainString();
    }

    private static String instant(Instant value) {
        return value == null ? "" : value.toString();
    }
}
