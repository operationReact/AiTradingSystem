package in.aitradingsystem.scanner;

import in.aitradingsystem.marketdata.MarketCandle;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * Connors-style RSI(2) mean reversion inside a long-term uptrend (daily bars).
 *
 * <p>Regime filter: close above the 200-day SMA. Trigger: the prior bar's 2-period RSI is below 10
 * (deep short-term oversold) and the latest close turns up (above the prior close). Stop is the
 * lowest low of the last 5 sessions; target is 2R.
 */
@Component
public final class SwingRsi2MeanReversionStrategy implements ScannerStrategy {
    public static final String NAME = "SWING_RSI2_MEANREV_V1";
    private static final int REGIME_SMA = 200;
    private static final int RSI_PERIOD = 2;
    private static final double OVERSOLD = 10.0;
    private static final int STOP_LOOKBACK = 5;
    private static final BigDecimal RISK_REWARD = new BigDecimal("2");

    @Override public String name() { return NAME; }
    @Override public int minimumCandles() { return REGIME_SMA + 1; }

    @Override
    public StrategyDescriptor descriptor() {
        return SwingSupport.dailyDescriptor(NAME, "1", "RSI(2) Mean Reversion in Uptrend (Swing)", minimumCandles());
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

        double rsiPrev = SwingIndicators.rsi(bars, last - 1, RSI_PERIOD);
        if (rsiPrev >= OVERSOLD) return Optional.empty();
        if (latest.close().compareTo(prev.close()) <= 0) return Optional.empty();

        BigDecimal stop = IndicatorMath.lowestLow(bars, last - (STOP_LOOKBACK - 1), last + 1);
        BigDecimal target = SwingSupport.rewardTarget(latest.close(), stop, RISK_REWARD);

        double score = 0.56 + Math.min(0.24, (OVERSOLD - rsiPrev) / 100.0 + 0.10);

        return SwingSupport.longCandidate(context, NAME, score, latest.close(), stop, target, latest.candleTime(),
                List.of("CLOSE_ABOVE_200_DAY_SMA_REGIME", "RSI2_OVERSOLD_BELOW_10", "SHORT_TERM_TURN_UP"));
    }
}
