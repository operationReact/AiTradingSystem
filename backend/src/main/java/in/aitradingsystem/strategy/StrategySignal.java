package in.aitradingsystem.strategy;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/** A deterministic, auditable candidate. It is not an order and never executes by itself. */
public record StrategySignal(
        String id,
        long instrumentId,
        String symbol,
        String exchange,
        String interval,
        String action,
        String strategy,
        BigDecimal referencePrice,
        BigDecimal stopLoss,
        BigDecimal target,
        BigDecimal fastAverage,
        BigDecimal slowAverage,
        BigDecimal averageVolume,
        BigDecimal latestVolume,
        double confidence,
        List<String> reasonCodes,
        String source,
        Instant createdAt,
        Instant latestCandleAt,
        String status) {}
