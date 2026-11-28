package in.aitradingsystem.scanner;

import in.aitradingsystem.marketdata.MarketCandle;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * Lower Bollinger-band re-entry inside a long-term uptrend (daily bars).
 *
 * <p>Regime filter: close above the 100-day SMA. Trigger: the prior close was below the lower band
 * (20-period SMA minus 2 standard deviations) and the latest close re-enters at or above the lower
 * band while closing higher than the prior bar. Stop is the lowest low of the last 5 sessions; target
 * is the 20-period middle band when it sits above entry, else 2R.
 */
@Component
public final class SwingBollingerReversionStrategy implements ScannerStrategy {
    public static final String NAME = "SWING_BOLLINGER_REVERSION_V1";
    private static final int REGIME_SMA = 100;
    private static final int BAND_PERIOD = 20;
    private static final double BAND_K = 2.0;
    private static final int STOP_LOOKBACK = 5;
    private static final BigDecimal RISK_REWARD = new BigDecimal("2");

    @Override public String name() { return NAME; }
    @Override public int minimumCandles() { return REGIME_SMA + 1; }

    @Override
    public StrategyDescriptor descriptor() {
        return SwingSupport.dailyDescriptor(NAME, "1", "Bollinger Lower-Band Reversion (Swing)", minimumCandles());
    }

    @Override
    public Optional<ScanCandidate> evaluate(ScanContext context) {
        if (SwingSupport.notDaily(context)) return Optional.empty();
        List<MarketCandle> bars = context.candles();
        if (bars.size() < minimumCandles()) return Optional.empty();
        int last = bars.size() - 1;
        MarketCandle latest = bars.get(last);
        MarketCandle prev = bars.get(last - 1);

        double regimeSma = SwingIndicators.sma(bars, last, REGIME_SMA);
        if (latest.close().doubleValue() <= regimeSma) return Optional.empty();

        double mid = SwingIndicators.sma(bars, last, BAND_PERIOD);
        double lower = mid - BAND_K * SwingIndicators.stdDev(bars, last, BAND_PERIOD);
        double midPrev = SwingIndicators.sma(bars, last - 1, BAND_PERIOD);
        double lowerPrev = midPrev - BAND_K * SwingIndicators.stdDev(bars, last - 1, BAND_PERIOD);

        boolean wasBelow = prev.close().doubleValue() < lowerPrev;
        boolean reEntered = latest.close().doubleValue() >= lower && latest.close().compareTo(prev.close()) > 0;
        if (!wasBelow || !reEntered) return Optional.empty();

        BigDecimal stop = IndicatorMath.lowestLow(bars, last - (STOP_LOOKBACK - 1), last + 1);
        BigDecimal midTarget = SwingSupport.bd(mid);
        BigDecimal target = midTarget.compareTo(latest.close()) > 0
                ? midTarget
                : SwingSupport.rewardTarget(latest.close(), stop, RISK_REWARD);

        double depth = (lowerPrev - prev.close().doubleValue()) / Math.max(0.01, Math.abs(lowerPrev));
        double score = 0.55 + Math.min(0.30, Math.max(0.0, depth) * 10.0);

        return SwingSupport.longCandidate(context, NAME, score, latest.close(), stop, target, latest.candleTime(),
                List.of("CLOSE_ABOVE_100_DAY_SMA_REGIME", "PRIOR_CLOSE_BELOW_LOWER_BAND", "CLOSE_REENTERED_LOWER_BAND"));
    }
}
