package in.aitradingsystem.scanner;

import in.aitradingsystem.marketdata.MarketCandle;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * Daily 20-day Donchian breakout filtered by a rising-trend regime.
 *
 * <p>Long research candidate when, on the latest completed daily bar, the close breaks above the
 * highest high of the prior 20 sessions and sits above its 50-day SMA. Stop is the lowest low of the
 * last 10 sessions; target is 2R. Long-only, deterministic, no lookahead.
 */
@Component
public final class SwingDonchianBreakoutStrategy implements ScannerStrategy {
    public static final String NAME = "SWING_DONCHIAN_BREAKOUT_V1";
    private static final int BREAKOUT_LOOKBACK = 20;
    private static final int TREND_SMA = 50;
    private static final int STOP_LOOKBACK = 10;
    private static final BigDecimal RISK_REWARD = new BigDecimal("2");

    @Override public String name() { return NAME; }
    @Override public int minimumCandles() { return TREND_SMA + 1; }

    @Override
    public StrategyDescriptor descriptor() {
        return SwingSupport.dailyDescriptor(NAME, "1", "Donchian 20-Day Breakout (Swing)", minimumCandles());
    }

    @Override
    public Optional<ScanCandidate> evaluate(ScanContext context) {
        if (SwingSupport.notDaily(context)) return Optional.empty();
        List<MarketCandle> bars = context.candles();
        if (bars.size() < minimumCandles()) return Optional.empty();
        int last = bars.size() - 1;
        MarketCandle latest = bars.get(last);
        BigDecimal close = latest.close();

        double trendSma = SwingIndicators.sma(bars, last, TREND_SMA);
        if (close.doubleValue() <= trendSma) return Optional.empty();

        BigDecimal priorHigh = IndicatorMath.highestHigh(bars, last - BREAKOUT_LOOKBACK, last);
        if (close.compareTo(priorHigh) <= 0) return Optional.empty();

        BigDecimal stop = IndicatorMath.lowestLow(bars, last - (STOP_LOOKBACK - 1), last + 1);
        BigDecimal target = SwingSupport.rewardTarget(close, stop, RISK_REWARD);

        double breakoutStrength = close.subtract(priorHigh)
                .divide(priorHigh.max(new BigDecimal("0.01")), 8, java.math.RoundingMode.HALF_UP).doubleValue();
        double score = 0.58 + Math.min(0.30, Math.max(0.0, breakoutStrength) * 20.0);

        return SwingSupport.longCandidate(context, NAME, score, close, stop, target, latest.candleTime(),
                List.of("CLOSE_ABOVE_50_DAY_SMA_TREND", "CLOSE_ABOVE_PRIOR_20_DAY_HIGH"));
    }
}
