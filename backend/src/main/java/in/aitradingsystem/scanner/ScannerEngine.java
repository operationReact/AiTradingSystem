package in.aitradingsystem.scanner;

import in.aitradingsystem.marketdata.CandleSeriesValidator;
import java.time.Duration;
import java.util.Comparator;
import java.util.List;

/**
 * Evaluates a fixed candle snapshot against registered deterministic strategies.
 * Invalid, future, stale, duplicate or out-of-order candle series fail closed before strategy evaluation.
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
        CandleSeriesValidator.Validation validation = CandleSeriesValidator.validate(
                context.candles(), context.evaluatedAt(), maxCandleAge);
        if (!validation.valid()) return List.of();

        return strategies.stream()
                .filter(strategy -> context.candles().size() >= strategy.minimumCandles())
                .map(strategy -> strategy.evaluate(context))
                .flatMap(java.util.Optional::stream)
                .sorted(Comparator.comparingDouble(ScanCandidate::score).reversed()
                        .thenComparing(ScanCandidate::strategy))
                .toList();
    }
}
