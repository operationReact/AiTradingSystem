package in.aitradingsystem.scanner;

import in.aitradingsystem.marketdata.MarketCandle;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * Pullback-to-trend continuation on daily bars.
 *
 * <p>In an uptrend (20-EMA above 50-EMA) the strategy waits for price to pull back to the 20-EMA
 * (prior bar's low at or below the 20-EMA) and then take a long only when the latest close reclaims
 * the 20-EMA and closes above the prior close. Stop is the lower of the last two lows; target is 2R.
 */
@Component
public final class SwingEmaPullbackStrategy implements ScannerStrategy {
    public static final String NAME = "SWING_EMA_PULLBACK_V1";
    private static final int FAST = 20;
    private static final int SLOW = 50;
    private static final BigDecimal RISK_REWARD = new BigDecimal("2");

    @Override public String name() { return NAME; }
    @Override public int minimumCandles() { return SLOW + 2; }

    @Override
    public StrategyDescriptor descriptor() {
        return SwingSupport.dailyDescriptor(NAME, "1", "20/50 EMA Pullback (Swing)", minimumCandles());
    }

    @Override
    public Optional<ScanCandidate> evaluate(ScanContext context) {
        if (SwingSupport.notDaily(context)) return Optional.empty();
        List<MarketCandle> bars = context.candles();
        if (bars.size() < minimumCandles()) return Optional.empty();
        int last = bars.size() - 1;
        MarketCandle latest = bars.get(last);
        MarketCandle prev = bars.get(last - 1);

        double fast = SwingIndicators.ema(bars, last, FAST);
        double slow = SwingIndicators.ema(bars, last, SLOW);
        if (fast <= slow) return Optional.empty();

        double fastPrev = SwingIndicators.ema(bars, last - 1, FAST);
        boolean pulledBack = prev.low().doubleValue() <= fastPrev;
        boolean reclaimed = latest.close().doubleValue() > fast && latest.close().compareTo(prev.close()) > 0;
        if (!pulledBack || !reclaimed) return Optional.empty();

        BigDecimal stop = latest.low().min(prev.low());
        BigDecimal target = SwingSupport.rewardTarget(latest.close(), stop, RISK_REWARD);

        double separation = (fast - slow) / Math.max(0.01, Math.abs(slow));
        double score = 0.55 + Math.min(0.30, Math.max(0.0, separation) * 15.0);

        return SwingSupport.longCandidate(context, NAME, score, latest.close(), stop, target, latest.candleTime(),
                List.of("FAST_EMA_ABOVE_SLOW_EMA_UPTREND", "PULLBACK_TO_20_EMA", "RECLAIM_CLOSE_ABOVE_20_EMA"));
    }
}
