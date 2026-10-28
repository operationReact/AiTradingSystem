package in.aitradingsystem.backtest;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Objects;

/**
 * Immutable, dependency-light description of a reproducible backtest run.
 *
 * <p>All execution assumptions that can change a result are explicit inputs. The canonical
 * fingerprint is stable for semantically identical specifications and can be persisted alongside
 * later P3 result evidence. This type deliberately contains no broker, LIVE execution, AI, or
 * market-data side effects.</p>
 */
public record BacktestRunSpecification(
        String strategyId,
        String strategyVersion,
        Instant fromInclusive,
        Instant toExclusive,
        BigDecimal initialCapital,
        BigDecimal slippageBps,
        BigDecimal brokerageBps) {

    private static final int MONEY_SCALE = 8;
    private static final int BPS_SCALE = 8;

    public BacktestRunSpecification {
        strategyId = requireText(strategyId, "strategyId");
        strategyVersion = requireText(strategyVersion, "strategyVersion");
        fromInclusive = Objects.requireNonNull(fromInclusive, "fromInclusive");
        toExclusive = Objects.requireNonNull(toExclusive, "toExclusive");
        if (!fromInclusive.isBefore(toExclusive)) {
            throw new IllegalArgumentException("fromInclusive must be before toExclusive");
        }
        initialCapital = requirePositive(initialCapital, "initialCapital").setScale(MONEY_SCALE, RoundingMode.UNNECESSARY);
        slippageBps = requireNonNegative(slippageBps, "slippageBps").setScale(BPS_SCALE, RoundingMode.UNNECESSARY);
        brokerageBps = requireNonNegative(brokerageBps, "brokerageBps").setScale(BPS_SCALE, RoundingMode.UNNECESSARY);
    }

    public String canonicalFingerprint() {
        String canonical = String.join("|",
                strategyId,
                strategyVersion,
                fromInclusive.toString(),
                toExclusive.toString(),
                initialCapital.toPlainString(),
                slippageBps.toPlainString(),
                brokerageBps.toPlainString());
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required by the Java runtime", e);
        }
    }

    private static String requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must be provided");
        }
        return value.trim();
    }

    private static BigDecimal requirePositive(BigDecimal value, String name) {
        Objects.requireNonNull(value, name);
        if (value.signum() <= 0) {
            throw new IllegalArgumentException(name + " must be positive");
        }
        return value;
    }

    private static BigDecimal requireNonNegative(BigDecimal value, String name) {
        Objects.requireNonNull(value, name);
        if (value.signum() < 0) {
            throw new IllegalArgumentException(name + " must be non-negative");
        }
        return value;
    }
}