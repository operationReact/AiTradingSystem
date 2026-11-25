package in.aitradingsystem.scanner;

import in.aitradingsystem.marketdata.CandleSeriesValidator;
import java.time.Duration;
import java.util.Comparator;
import java.util.List;
import java.util.Set;

/**
 * Evaluates a fixed candle snapshot against registered deterministic strategies.
 * Invalid, future, stale, duplicate or out-of-order candle series fail closed before strategy evaluation.
 *
 * <p>{@link #evaluate} returns one outcome per registered strategy, so a strategy that
 * ran and found nothing is distinguishable from one that was never evaluated.
 */
public final class ScannerEngine {
    public static final String CANDIDATE = "CANDIDATE";
    public static final String NO_SIGNAL = "NO_SIGNAL";
    public static final String NOT_EVALUATED = "NOT_EVALUATED";
    private static final Set<String> OUTCOMES = Set.of(CANDIDATE, NO_SIGNAL, NOT_EVALUATED);

    /** One strategy's outcome for one candle snapshot. A candidate is present only for CANDIDATE. */
    public record StrategyEvaluation(String strategy, String outcome, String reasonCode, ScanCandidate candidate) {
        public StrategyEvaluation {
            if (strategy == null || strategy.isBlank() || strategy.length() > 64) {
                throw new IllegalArgumentException("strategy is required");
            }
            if (!OUTCOMES.contains(outcome)) throw new IllegalArgumentException("unknown outcome: " + outcome);
            if (reasonCode == null || reasonCode.isBlank() || reasonCode.length() > 80) {
                throw new IllegalArgumentException("reasonCode is required");
            }
            if (CANDIDATE.equals(outcome) != (candidate != null)) {
                throw new IllegalArgumentException("A candidate is required for CANDIDATE and forbidden otherwise");
            }
        }
    }

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

    /** Strategy codes in registration order, used to record outcomes that were never evaluated. */
    public List<String> strategyCodes() {
        return strategies.stream().map(ScannerStrategy::name).toList();
    }

    public List<StrategyEvaluation> evaluate(ScanContext context) {
        if (context == null) throw new IllegalArgumentException("context is required");
        CandleSeriesValidator.Validation validation = CandleSeriesValidator.validate(
                context.candles(), context.evaluatedAt(), maxCandleAge);
        if (!validation.valid()) {
            return strategies.stream()
                    .map(strategy -> new StrategyEvaluation(strategy.name(), NOT_EVALUATED, "INVALID_CANDLE_SERIES", null))
                    .toList();
        }
        return strategies.stream().map(strategy -> {
            if (context.candles().size() < strategy.minimumCandles()) {
                return new StrategyEvaluation(strategy.name(), NOT_EVALUATED, "INSUFFICIENT_HISTORY", null);
            }
            return strategy.evaluate(context)
                    .map(candidate -> new StrategyEvaluation(strategy.name(), CANDIDATE, "CANDIDATE", candidate))
                    .orElseGet(() -> new StrategyEvaluation(strategy.name(), NO_SIGNAL, "NO_SIGNAL", null));
        }).toList();
    }

    /** Candidates from an evaluation, in the same deterministic order {@link #scan} always used. */
    public static List<ScanCandidate> candidates(List<StrategyEvaluation> evaluations) {
        return evaluations.stream()
                .filter(evaluation -> CANDIDATE.equals(evaluation.outcome()))
                .map(StrategyEvaluation::candidate)
                .sorted(Comparator.comparingDouble(ScanCandidate::score).reversed()
                        .thenComparing(ScanCandidate::strategy))
                .toList();
    }

    public List<ScanCandidate> scan(ScanContext context) {
        return candidates(evaluate(context));
    }
}
