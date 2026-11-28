package in.aitradingsystem.scanner;

import in.aitradingsystem.marketdata.MarketCandle;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * Trend-continuation with an ATR-volatility stop (daily bars).
 *
 * <p>Trend filter: close above a rising 50-day SMA (SMA50 today above SMA50 twenty sessions ago).
 * Trigger: the latest close is both above the prior close and above the 20-day SMA. Stop is placed
 * 2x the 14-day ATR below the close; target is 2R of that ATR-scaled risk.
 */
@Component
public final class SwingAtrTrendStrategy implements ScannerStrategy {
    public static final String NAME = "SWING_ATR_TREND_V1";
    private static final int TREND_SMA = 50;
    private static final int SLOPE_LOOKBACK = 20;
    private static final int ENTRY_SMA = 20;
    private static final int ATR_PERIOD = 14;
    private static final BigDecimal ATR_STOP_MULTIPLE = new BigDecimal("2");
    private static final BigDecimal RISK_REWARD = new BigDecimal("2");

    @Override public String name() { return NAME; }
    @Override public int minimumCandles() { return TREND_SMA + SLOPE_LOOKBACK + 1; }

    @Override
    public StrategyDescriptor descriptor() {
        return SwingSupport.dailyDescriptor(NAME, "1", "ATR-Stop Trend Continuation (Swing)", minimumCandles());
    }

    @Override
    public Optional<ScanCandidate> evaluate(ScanContext context) {
        if (SwingSupport.notDaily(context)) return Optional.empty();
        List<MarketCandle> bars = context.candles();
        if (bars.size() < minimumCandles()) return Optional.empty();
        int last = bars.size() - 1;
        MarketCandle latest = bars.get(last);
        MarketCandle prev = bars.get(last - 1);

        double trendSma = SwingIndicators.sma(bars, last, TREND_SMA);
        double trendSmaPast = SwingIndicators.sma(bars, last - SLOPE_LOOKBACK, TREND_SMA);
        double entrySma = SwingIndicators.sma(bars, last, ENTRY_SMA);

        boolean risingTrend = latest.close().doubleValue() > trendSma && trendSma > trendSmaPast;
        boolean continuation = latest.close().compareTo(prev.close()) > 0 && latest.close().doubleValue() > entrySma;
        if (!risingTrend || !continuation) return Optional.empty();

        double atr = SwingIndicators.atr(bars, last, ATR_PERIOD);
        if (atr <= 0.0) return Optional.empty();
        BigDecimal stop = latest.close().subtract(ATR_STOP_MULTIPLE.multiply(SwingSupport.bd(atr)));
        BigDecimal target = SwingSupport.rewardTarget(latest.close(), stop, RISK_REWARD);

        double slope = (trendSma - trendSmaPast) / Math.max(0.01, Math.abs(trendSmaPast));
        double score = 0.56 + Math.min(0.30, Math.max(0.0, slope) * 10.0);

        return SwingSupport.longCandidate(context, NAME, score, latest.close(), stop, target, latest.candleTime(),
                List.of("CLOSE_ABOVE_RISING_50_DAY_SMA", "CLOSE_ABOVE_20_DAY_SMA", "ATR_VOLATILITY_STOP"));
    }
}
