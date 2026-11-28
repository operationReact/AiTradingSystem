package in.aitradingsystem.scanner;

import in.aitradingsystem.marketdata.MarketCandle;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * MACD(12,26,9) bullish signal-line crossover above the zero line (daily bars).
 *
 * <p>Long research candidate when the MACD line crosses from at-or-below the signal line to above it
 * on the latest bar while the MACD line is positive (bullish regime). Stop is the lowest low of the
 * last 10 sessions; target is 2R.
 */
@Component
public final class SwingMacdCrossStrategy implements ScannerStrategy {
    public static final String NAME = "SWING_MACD_CROSS_V1";
    private static final int FAST = 12;
    private static final int SLOW = 26;
    private static final int SIGNAL = 9;
    private static final int STOP_LOOKBACK = 10;
    private static final BigDecimal RISK_REWARD = new BigDecimal("2");

    @Override public String name() { return NAME; }
    @Override public int minimumCandles() { return 60; }

    @Override
    public StrategyDescriptor descriptor() {
        return SwingSupport.dailyDescriptor(NAME, "1", "MACD Bullish Zero-Line Cross (Swing)", minimumCandles());
    }

    @Override
    public Optional<ScanCandidate> evaluate(ScanContext context) {
        if (SwingSupport.notDaily(context)) return Optional.empty();
        List<MarketCandle> bars = context.candles();
        if (bars.size() < minimumCandles()) return Optional.empty();
        int last = bars.size() - 1;
        MarketCandle latest = bars.get(last);

        double lineNow = SwingIndicators.macdLine(bars, last, FAST, SLOW);
        double signalNow = SwingIndicators.macdSignal(bars, last, FAST, SLOW, SIGNAL);
        double linePrev = SwingIndicators.macdLine(bars, last - 1, FAST, SLOW);
        double signalPrev = SwingIndicators.macdSignal(bars, last - 1, FAST, SLOW, SIGNAL);

        boolean crossedUp = linePrev <= signalPrev && lineNow > signalNow;
        boolean bullishRegime = lineNow > 0.0;
        if (!crossedUp || !bullishRegime) return Optional.empty();

        BigDecimal stop = IndicatorMath.lowestLow(bars, last - (STOP_LOOKBACK - 1), last + 1);
        BigDecimal target = SwingSupport.rewardTarget(latest.close(), stop, RISK_REWARD);

        double histogram = lineNow - signalNow;
        double score = 0.57 + Math.min(0.28, Math.max(0.0, histogram) / Math.max(0.01, Math.abs(latest.close().doubleValue())) * 50.0);

        return SwingSupport.longCandidate(context, NAME, score, latest.close(), stop, target, latest.candleTime(),
                List.of("MACD_CROSSED_ABOVE_SIGNAL", "MACD_LINE_ABOVE_ZERO"));
    }
}
