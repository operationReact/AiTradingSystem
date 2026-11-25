package in.aitradingsystem.scanner;

import in.aitradingsystem.marketdata.HistoricalCandleInterval;
import in.aitradingsystem.marketdata.MarketCandle;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.springframework.stereotype.Component;

/** Long-only RSI recovery candidate: prior RSI below 30, current RSI recovers above 30 with price confirmation. */
@Component
public final class RsiReversalStrategy implements ScannerStrategy {
    public static final String NAME = "RSI_REVERSAL_V1";
    private static final int PERIOD = 14;
    private static final BigDecimal TWO = new BigDecimal("2");

    @Override public String name() { return NAME; }
    @Override public int minimumCandles() { return PERIOD + 2; }

    @Override
    public StrategyDescriptor descriptor() {
        return new StrategyDescriptor(NAME, "1", "RSI Reversal",
                Set.of(HistoricalCandleInterval.ONE_MINUTE, HistoricalCandleInterval.THREE_MINUTE,
                        HistoricalCandleInterval.FIVE_MINUTE, HistoricalCandleInterval.TEN_MINUTE,
                        HistoricalCandleInterval.FIFTEEN_MINUTE, HistoricalCandleInterval.THIRTY_MINUTE,
                        HistoricalCandleInterval.ONE_HOUR),
                minimumCandles(), TradingStyle.INTRADAY, false, "HEALTHY", true, false);
    }

    @Override
    public Optional<ScanCandidate> evaluate(ScanContext context) {
        List<MarketCandle> bars = context.candles();
        if (bars.size() < minimumCandles()) return Optional.empty();
        double previousRsi = rsi(bars, bars.size() - 2);
        double currentRsi = rsi(bars, bars.size() - 1);
        MarketCandle previous = bars.get(bars.size() - 2);
        MarketCandle latest = bars.getLast();
        if (!(previousRsi < 30.0 && currentRsi >= 30.0 && latest.close().compareTo(previous.close()) > 0)) {
            return Optional.empty();
        }
        BigDecimal stop = IndicatorMath.lowestLow(bars, Math.max(0, bars.size() - 5), bars.size())
                .setScale(2, RoundingMode.HALF_UP);
        if (stop.signum() <= 0 || stop.compareTo(latest.close()) >= 0) return Optional.empty();
        BigDecimal target = latest.close().add(latest.close().subtract(stop).multiply(TWO)).setScale(2, RoundingMode.HALF_UP);
        double score = IndicatorMath.clamp01(0.56 + Math.min(0.24, (currentRsi - 30.0) / 100.0));
        return Optional.of(new ScanCandidate(context.instrumentId(), context.tradingSymbol(), context.exchange(),
                context.interval(), NAME, score, latest.close(), stop, target, latest.candleTime(),
                List.of("RSI_RECOVERED_ABOVE_30", "PRICE_RECOVERY_CONFIRMED", "RESEARCH_CANDIDATE_ONLY")));
    }

    private double rsi(List<MarketCandle> bars, int endIndex) {
        int start = endIndex - PERIOD;
        if (start < 0) return 50.0;
        double gains = 0.0;
        double losses = 0.0;
        for (int i = start + 1; i <= endIndex; i++) {
            double change = bars.get(i).close().subtract(bars.get(i - 1).close()).doubleValue();
            if (change > 0) gains += change; else losses -= change;
        }
        if (losses == 0.0) return gains == 0.0 ? 50.0 : 100.0;
        double rs = (gains / PERIOD) / (losses / PERIOD);
        return 100.0 - (100.0 / (1.0 + rs));
    }
}
