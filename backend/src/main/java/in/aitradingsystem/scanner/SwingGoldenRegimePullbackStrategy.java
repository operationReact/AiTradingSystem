package in.aitradingsystem.scanner;

import in.aitradingsystem.marketdata.MarketCandle;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * Pullback to the 50-day SMA inside a golden-cross regime (daily bars).
 *
 * <p>Regime filter: 50-day SMA above the 200-day SMA. Trigger: the latest bar dips to or below the
 * 50-day SMA intrabar (low ≤ SMA50) but closes back above it with a bullish body (close &gt; open).
 * Stop is the latest bar's low; target is 2R.
 */
@Component
public final class SwingGoldenRegimePullbackStrategy implements ScannerStrategy {
    public static final String NAME = "SWING_GOLDEN_REGIME_PULLBACK_V1";
    private static final int FAST_SMA = 50;
    private static final int SLOW_SMA = 200;
    private static final BigDecimal RISK_REWARD = new BigDecimal("2");

    @Override public String name() { return NAME; }
    @Override public int minimumCandles() { return SLOW_SMA + 1; }

    @Override
    public StrategyDescriptor descriptor() {
        return SwingSupport.dailyDescriptor(NAME, "1", "Golden-Cross 50-SMA Pullback (Swing)", minimumCandles());
    }

    @Override
    public Optional<ScanCandidate> evaluate(ScanContext context) {
        if (SwingSupport.notDaily(context)) return Optional.empty();
        List<MarketCandle> bars = context.candles();
        if (bars.size() < minimumCandles()) return Optional.empty();
        int last = bars.size() - 1;
        MarketCandle latest = bars.get(last);

        double fastSma = SwingIndicators.sma(bars, last, FAST_SMA);
        double slowSma = SwingIndicators.sma(bars, last, SLOW_SMA);
        if (fastSma <= slowSma) return Optional.empty();

        boolean dipped = latest.low().doubleValue() <= fastSma;
        boolean held = latest.close().doubleValue() > fastSma;
        boolean bullishBody = latest.close().compareTo(latest.open()) > 0;
        if (!dipped || !held || !bullishBody) return Optional.empty();

        BigDecimal stop = latest.low();
        BigDecimal target = SwingSupport.rewardTarget(latest.close(), stop, RISK_REWARD);

        double regimeStrength = (fastSma - slowSma) / Math.max(0.01, Math.abs(slowSma));
        double score = 0.56 + Math.min(0.30, Math.max(0.0, regimeStrength) * 8.0);

        return SwingSupport.longCandidate(context, NAME, score, latest.close(), stop, target, latest.candleTime(),
                List.of("GOLDEN_REGIME_50_SMA_ABOVE_200_SMA", "PULLBACK_TO_50_DAY_SMA", "BULLISH_RECLAIM_CLOSE"));
    }
}
