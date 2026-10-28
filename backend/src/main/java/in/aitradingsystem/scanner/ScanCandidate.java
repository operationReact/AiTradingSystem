package in.aitradingsystem.scanner;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/** Research-only scanner result. A candidate is never an executable order. */
public record ScanCandidate(
        long instrumentId,
        String tradingSymbol,
        String exchange,
        String interval,
        String strategy,
        double score,
        BigDecimal referencePrice,
        BigDecimal suggestedStop,
        BigDecimal suggestedTarget,
        Instant candleTime,
        List<String> reasons) {

    public ScanCandidate {
        if (strategy == null || strategy.isBlank()) throw new IllegalArgumentException("strategy is required");
        if (!Double.isFinite(score) || score < 0.0 || score > 1.0) {
            throw new IllegalArgumentException("score must be between 0 and 1");
        }
        reasons = reasons == null ? List.of() : List.copyOf(reasons);
    }
}
