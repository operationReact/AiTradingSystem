package in.aitradingsystem.scanner;

import in.aitradingsystem.marketdata.MarketCandle;
import java.time.Duration;
import java.util.Comparator;
import java.util.List;

/**
 * Evaluates a fixed candle snapshot against registered deterministic strategies.
 * The engine rejects future/stale snapshots before any strategy is evaluated.
 */
public final class ScannerEngine {
    private final List<ScannerStrategy> strategies;
    private final Duration maxCandleAge;

    public ScannerEngine(List<ScannerStrategy> strategies, Duration maxCandleAge) {
        if (strategies == null || strategies.isEmpty()) throw new IllegalArgumentException("At least one scanner strategy is required");
        if (maxCandleAge == null || maxCandleAge.isNegative() || maxCandleAge.isZero()) {
            throw new IllegalArgumentException("maxCandleAge must be positive");
        }
        this.strategies = List.copyOf(strategies);
        this.maxCandleAge = maxCandleAge;
    }

    public List<ScanCandidate> scan(ScanContext context) {
        if (context == null) throw new IllegalArgumentException("context is required");
        if (context.candles().isEmpty()) return List.of();

        MarketCandle latest = context.candles().getLast();
        if (latest.candleTime().isAfter(context.evaluatedAt())) return List.of();
        if (Duration.between(latest.candleTime(), context.evaluatedAt()).compareTo(maxCandleAge) > 0) return List.of();

        return strategies.stream()
                .filter(strategy -> context.candles().size() >= strategy.minimumCandles())
                .map(strategy -> strategy.evaluate(context))
                .flatMap(java.util.Optional::stream)
                .sorted(Comparator.comparingDouble(ScanCandidate::score).reversed()
                        .thenComparing(ScanCandidate::strategy))
                .toList();
    }
}
