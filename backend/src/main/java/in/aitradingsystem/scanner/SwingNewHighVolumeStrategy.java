package in.aitradingsystem.scanner;

import in.aitradingsystem.marketdata.MarketCandle;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * New 60-day high with a volume expansion (daily momentum breakout).
 *
 * <p>Long research candidate when the latest close is at or above the highest high of the prior 60
 * sessions and the latest volume is at least 1.5x the 20-day average volume. Stop is the lowest low
 * of the last 10 sessions; target is 2R.
 */
@Component
public final class SwingNewHighVolumeStrategy implements ScannerStrategy {
    public static final String NAME = "SWING_NEW_HIGH_VOLUME_V1";
    private static final int HIGH_LOOKBACK = 60;
    private static final int VOLUME_LOOKBACK = 20;
    private static final double VOLUME_MULTIPLE = 1.5;
    private static final int STOP_LOOKBACK = 10;
    private static final BigDecimal RISK_REWARD = new BigDecimal("2");

    @Override public String name() { return NAME; }
    @Override public int minimumCandles() { return HIGH_LOOKBACK + 2; }

    @Override
    public StrategyDescriptor descriptor() {
        return SwingSupport.dailyDescriptor(NAME, "1", "60-Day High + Volume Expansion (Swing)", minimumCandles());
    }

    @Override
    public Optional<ScanCandidate> evaluate(ScanContext context) {
        if (SwingSupport.notDaily(context)) return Optional.empty();
        List<MarketCandle> bars = context.candles();
        if (bars.size() < minimumCandles()) return Optional.empty();
        int last = bars.size() - 1;
        MarketCandle latest = bars.get(last);

        BigDecimal priorHigh = IndicatorMath.highestHigh(bars, last - HIGH_LOOKBACK, last);
        if (latest.close().compareTo(priorHigh) < 0) return Optional.empty();

        double avgVolume = IndicatorMath.averageVolume(bars, last - VOLUME_LOOKBACK, last).doubleValue();
        if (avgVolume <= 0.0 || latest.volume() < VOLUME_MULTIPLE * avgVolume) return Optional.empty();

        BigDecimal stop = IndicatorMath.lowestLow(bars, last - (STOP_LOOKBACK - 1), last + 1);
        BigDecimal target = SwingSupport.rewardTarget(latest.close(), stop, RISK_REWARD);

        double volumeRatio = latest.volume() / Math.max(1.0, avgVolume);
        double score = 0.58 + Math.min(0.30, (volumeRatio - VOLUME_MULTIPLE) * 0.15);

        return SwingSupport.longCandidate(context, NAME, score, latest.close(), stop, target, latest.candleTime(),
                List.of("CLOSE_AT_OR_ABOVE_60_DAY_HIGH", "VOLUME_AT_LEAST_1_5X_20_DAY_AVERAGE"));
    }
}
