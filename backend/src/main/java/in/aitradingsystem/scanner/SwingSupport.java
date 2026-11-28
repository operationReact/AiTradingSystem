package in.aitradingsystem.scanner;

import in.aitradingsystem.marketdata.HistoricalCandleInterval;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Shared, deterministic scaffolding for {@link TradingStyle#SWING} strategies.
 *
 * <p>Swing strategies operate on completed daily bars only. They are long-only research candidates:
 * a candidate is never an order. All of them route their final stop/target through
 * {@link #longCandidate} so the invariant stop &lt; reference &lt; target holds everywhere and a
 * malformed level fails closed to "no signal" rather than emitting an un-executable candidate.
 */
final class SwingSupport {
    static final String RESEARCH_ONLY = "RESEARCH_CANDIDATE_ONLY";
    static final Set<HistoricalCandleInterval> DAILY_ONLY = Set.of(HistoricalCandleInterval.ONE_DAY);

    private SwingSupport() {}

    /** Swing strategies evaluate daily bars only; any other interval is inert (empty) by design. */
    static boolean notDaily(ScanContext context) {
        return !HistoricalCandleInterval.ONE_DAY.name().equals(context.interval());
    }

    static StrategyDescriptor dailyDescriptor(String code, String version, String displayName, int minimumCandles) {
        return new StrategyDescriptor(code, version, displayName, DAILY_ONLY, minimumCandles,
                TradingStyle.SWING, false, "HEALTHY", true, false);
    }

    static BigDecimal bd(double value) {
        return new BigDecimal(Double.toString(value));
    }

    /** Target that is {@code riskReward} times the entry-to-stop distance above the reference. */
    static BigDecimal rewardTarget(BigDecimal reference, BigDecimal stop, BigDecimal riskReward) {
        return reference.add(reference.subtract(stop).multiply(riskReward));
    }

    /**
     * Builds a validated long research candidate, or empty when the stop/target are not a tradable
     * long setup. Reference/stop/target are rounded to 2 dp; {@link #RESEARCH_ONLY} is always appended.
     */
    static Optional<ScanCandidate> longCandidate(ScanContext context, String code, double score,
            BigDecimal reference, BigDecimal stop, BigDecimal target, Instant candleTime, List<String> reasons) {
        BigDecimal ref = reference.setScale(2, RoundingMode.HALF_UP);
        BigDecimal st = stop.setScale(2, RoundingMode.HALF_UP);
        BigDecimal tg = target.setScale(2, RoundingMode.HALF_UP);
        if (st.signum() <= 0 || st.compareTo(ref) >= 0 || tg.compareTo(ref) <= 0) {
            return Optional.empty();
        }
        List<String> allReasons = new ArrayList<>(reasons);
        if (!allReasons.contains(RESEARCH_ONLY)) allReasons.add(RESEARCH_ONLY);
        return Optional.of(new ScanCandidate(context.instrumentId(), context.tradingSymbol(), context.exchange(),
                context.interval(), code, IndicatorMath.clamp01(score), ref, st, tg, candleTime,
                List.copyOf(allReasons)));
    }
}
