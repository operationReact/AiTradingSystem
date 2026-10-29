package in.aitradingsystem.backtest;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/** Immutable strategy output. A non-triggered signal deliberately carries no prices or reasons. */
public record BacktestSignal(
        boolean triggered,
        BigDecimal referencePrice,
        BigDecimal stopPrice,
        BigDecimal targetPrice,
        Instant signalTime,
        List<String> reasons) {

    public BacktestSignal {
        reasons = reasons == null ? List.of() : List.copyOf(reasons);
        if (!triggered && (referencePrice != null || stopPrice != null || targetPrice != null || signalTime != null || !reasons.isEmpty())) {
            throw new IllegalArgumentException("non-triggered signal cannot carry trade data");
        }
        if (triggered && (referencePrice == null || stopPrice == null || targetPrice == null || signalTime == null)) {
            throw new IllegalArgumentException("triggered signal requires prices and signalTime");
        }
    }

    public static BacktestSignal none() {
        return new BacktestSignal(false, null, null, null, null, List.of());
    }
}
