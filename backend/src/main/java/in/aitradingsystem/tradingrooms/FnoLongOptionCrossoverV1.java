package in.aitradingsystem.tradingrooms;

import java.math.BigDecimal;
import java.math.MathContext;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * Deterministic research-only NIFTY directional long-option signal.
 *
 * <p>A completed underlying (NIFTY index) candle EMA8/EMA21 crossover maps to the
 * existing Mind catalog Long Call (STR-181) or Long Put (STR-182). This class
 * does not choose an expiry/strike, inspect option quotes, submit an order,
 * grant market-data rights or authorize LIVE/PAPER execution. Consumers MUST
 * separately prove canonical source permissions, F&O contract identity, fresh
 * exchange-time option bid/ask, market session, margin/cash and execution consent.
 *
 * <p>Never feed an incomplete candle or forward-known provider observation.
 */
public final class FnoLongOptionCrossoverV1 {
    private FnoLongOptionCrossoverV1() {}

    private static final int MIN_BARS = 35;
    private static final int MAX_BARS = 512;
    private static final Duration MAX_STALENESS = Duration.ofDays(4);
    private static final MathContext MC = MathContext.DECIMAL64;
    private static final BigDecimal FAST_ALPHA = BigDecimal.valueOf(2).divide(BigDecimal.valueOf(9), MC);
    private static final BigDecimal SLOW_ALPHA = BigDecimal.valueOf(2).divide(BigDecimal.valueOf(22), MC);
    public static final String UNDERLYING = "NSE_INDEX|Nifty 50";

    public record CompletedBar(Instant exchangeCloseAt, Instant observedAt, BigDecimal close) {}

    public enum Status { BLOCKED_RIGHTS, BLOCKED_DATA, NO_CROSSOVER, RESEARCH_CANDIDATE }

    public record Decision(Status status, String strategyId, String optionType,
                           String reason, Instant asOf, BigDecimal fastEma,
                           BigDecimal slowEma) {
        public boolean executable() {
            return false; // An EMA research candidate cannot place orders.
        }
    }

    /**
     * @param underlyingKey must be canonical Upstox/NSE NIFTY underlying
     * @param rightsVerified only a server-side verified source-use permission
     * @param bars completed daily bars, strictly ascending in exchange-time,
     *             with provider observation at/after each candle close
     * @param asOf reproducible evaluation instant; no source field may exceed it
     */
    public static Decision evaluate(String underlyingKey, boolean rightsVerified,
                                    List<CompletedBar> bars, Instant asOf) {
        Objects.requireNonNull(asOf, "asOf");
        if (!rightsVerified) {
            return new Decision(Status.BLOCKED_RIGHTS, null, null,
                    "RESEARCH_SOURCE_RIGHTS_NOT_VERIFIED", asOf, null, null);
        }
        if (!UNDERLYING.equals(underlyingKey) || bars == null
                || bars.size() < MIN_BARS || bars.size() > MAX_BARS) {
            return blocked(asOf, "CANONICAL_NIFTY_CLOSED_CANDLES_UNAVAILABLE");
        }
        Instant previousCloseAt = null;
        for (CompletedBar bar : bars) {
            if (bar == null || bar.exchangeCloseAt() == null
                    || bar.observedAt() == null || bar.close() == null
                    || bar.close().signum() <= 0
                    || bar.observedAt().isBefore(bar.exchangeCloseAt())
                    || bar.observedAt().isAfter(asOf)
                    || bar.exchangeCloseAt().isAfter(asOf)
                    || (previousCloseAt != null
                        && !bar.exchangeCloseAt().isAfter(previousCloseAt))) {
                return blocked(asOf, "INCOMPLETE_OR_FORWARD_KNOWN_CANDLE");
            }
            previousCloseAt = bar.exchangeCloseAt();
        }
        if (Duration.between(previousCloseAt, asOf).compareTo(MAX_STALENESS) > 0) {
            return blocked(asOf, "UNDERLYING_DATA_STALE");
        }

        BigDecimal fast = bars.getFirst().close();
        BigDecimal slow = fast;
        BigDecimal prevFast = null;
        BigDecimal prevSlow = null;
        for (int i = 1; i < bars.size(); i++) {
            prevFast = fast;
            prevSlow = slow;
            BigDecimal close = bars.get(i).close();
            fast = ema(fast, close, FAST_ALPHA);
            slow = ema(slow, close, SLOW_ALPHA);
        }
        // We explicitly test the previous completed candle: trend direction
        // alone is NOT a crossover and must not generate recurring signals.
        if (prevFast.compareTo(prevSlow) <= 0 && fast.compareTo(slow) > 0) {
            return new Decision(Status.RESEARCH_CANDIDATE, "strategy.STR-181",
                    "CE", "EMA8_CROSSED_ABOVE_EMA21", asOf, fast, slow);
        }
        if (prevFast.compareTo(prevSlow) >= 0 && fast.compareTo(slow) < 0) {
            return new Decision(Status.RESEARCH_CANDIDATE, "strategy.STR-182",
                    "PE", "EMA8_CROSSED_BELOW_EMA21", asOf, fast, slow);
        }
        return new Decision(Status.NO_CROSSOVER, null, null,
                "NO_NEW_DIRECTIONAL_CROSSOVER", asOf, fast, slow);
    }

    private static BigDecimal ema(BigDecimal prior, BigDecimal close, BigDecimal alpha) {
        return prior.add(close.subtract(prior, MC).multiply(alpha, MC), MC);
    }

    private static Decision blocked(Instant asOf, String reason) {
        return new Decision(Status.BLOCKED_DATA, null, null, reason, asOf, null, null);
    }
}
