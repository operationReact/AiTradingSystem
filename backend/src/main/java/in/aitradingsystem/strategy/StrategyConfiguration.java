package in.aitradingsystem.strategy;

import java.math.BigDecimal;
import java.util.Objects;

/** Immutable, validated snapshot of a strategy definition used for deterministic evaluation. */
public record StrategyConfiguration(
        String strategyId,
        int version,
        int fastPeriod,
        int slowPeriod,
        BigDecimal targetRiskReward,
        BigDecimal stopBufferPercent,
        int maxSignalAgeMinutes) {

    public StrategyConfiguration {
        strategyId = Objects.requireNonNull(strategyId, "strategyId is required").trim();
        if (strategyId.isEmpty()) throw new IllegalArgumentException("strategyId is required");
        if (version < 1) throw new IllegalArgumentException("strategy version must be positive");
        if (fastPeriod < 2 || slowPeriod <= fastPeriod || slowPeriod > 500) {
            throw new IllegalArgumentException("Strategy periods must satisfy 2 <= fastPeriod < slowPeriod <= 500");
        }
        if (targetRiskReward == null || targetRiskReward.compareTo(BigDecimal.ONE) < 0
                || targetRiskReward.compareTo(new BigDecimal("5")) > 0) {
            throw new IllegalArgumentException("targetRiskReward must be between 1 and 5");
        }
        if (stopBufferPercent == null || stopBufferPercent.compareTo(BigDecimal.ZERO) <= 0
                || stopBufferPercent.compareTo(new BigDecimal("10")) > 0) {
            throw new IllegalArgumentException("stopBufferPercent must be greater than 0 and at most 10");
        }
        if (maxSignalAgeMinutes < 1 || maxSignalAgeMinutes > 10080) {
            throw new IllegalArgumentException("maxSignalAgeMinutes must be between 1 and 10080");
        }
        targetRiskReward = targetRiskReward.stripTrailingZeros();
        stopBufferPercent = stopBufferPercent.stripTrailingZeros();
    }

    public String identity() {
        return strategyId + ":v" + version;
    }
}
