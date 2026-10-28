package in.aitradingsystem.scanner;

import in.aitradingsystem.marketdata.MarketCandle;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Optional;

/** Reclaim of the 20-bar SMA while the 5-bar SMA is above the 20-bar SMA. */
public final class MovingAverageReclaimStrategy implements ScannerStrategy {
    public static final String NAME = "MA_RECLAIM_V1";
    private static final int FAST = 5;
    private static final int SLOW = 20;
    private static final BigDecimal TWO = new BigDecimal("2");

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public int minimumCandles() {
        return SLOW + 1;
    }

    @Override
    public Optional<ScanCandidate> evaluate(ScanContext context) {
        List<MarketCandle> bars = context.candles();
        if (bars.size() < minimumCandles()) return Optional.empty();

        int last = bars.size() - 1;
        MarketCandle latest = bars.get(last);
        MarketCandle previous = bars.get(last - 1);

        BigDecimal currentSlow = IndicatorMath.averageClose(bars, bars.size() - SLOW, bars.size());
        BigDecimal currentFast = IndicatorMath.averageClose(bars, bars.size() - FAST, bars.size());
        BigDecimal previousSlow = IndicatorMath.averageClose(bars, bars.size() - SLOW - 1, bars.size() - 1);

        boolean reclaimed = previous.close().compareTo(previousSlow) <= 0 && latest.close().compareTo(currentSlow) > 0;
        boolean bullishStructure = currentFast.compareTo(currentSlow) > 0;
        if (!reclaimed || !bullishStructure) return Optional.empty();

        BigDecimal stop = latest.low().min(previous.low()).setScale(2, RoundingMode.HALF_UP);
        if (stop.signum() <= 0 || stop.compareTo(latest.close()) >= 0) return Optional.empty();
        BigDecimal risk = latest.close().subtract(stop);
        BigDecimal target = latest.close().add(risk.multiply(TWO)).setScale(2, RoundingMode.HALF_UP);

        double separation = currentFast.subtract(currentSlow)
                .divide(currentSlow.abs().max(new BigDecimal("0.01")), 8, RoundingMode.HALF_UP)
                .doubleValue();
        double score = IndicatorMath.clamp01(0.55 + Math.min(0.35, Math.max(0.0, separation) * 20.0));

        return Optional.of(new ScanCandidate(
                context.instrumentId(), context.tradingSymbol(), context.exchange(), context.interval(), NAME,
                score, latest.close(), stop, target, latest.candleTime(),
                List.of("PREVIOUS_CLOSE_AT_OR_BELOW_SLOW_SMA", "LATEST_CLOSE_RECLAIMED_SLOW_SMA",
                        "FAST_SMA_ABOVE_SLOW_SMA", "RESEARCH_CANDIDATE_ONLY")));
    }
}
