package in.aitradingsystem.scanner;

import in.aitradingsystem.marketdata.MarketCandle;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Optional;

/** Breakout above the prior 20-bar high with >= 1.5x prior average volume. */
public final class VolumeBreakoutStrategy implements ScannerStrategy {
    public static final String NAME = "VOLUME_BREAKOUT_V1";
    private static final int LOOKBACK = 20;
    private static final BigDecimal MIN_VOLUME_MULTIPLE = new BigDecimal("1.5");
    private static final BigDecimal TWO = new BigDecimal("2");

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public int minimumCandles() {
        return LOOKBACK + 1;
    }

    @Override
    public Optional<ScanCandidate> evaluate(ScanContext context) {
        List<MarketCandle> bars = context.candles();
        if (bars.size() < minimumCandles()) return Optional.empty();

        int lastIndex = bars.size() - 1;
        MarketCandle latest = bars.get(lastIndex);
        int start = lastIndex - LOOKBACK;
        BigDecimal priorHigh = IndicatorMath.highestHigh(bars, start, lastIndex);
        BigDecimal averageVolume = IndicatorMath.averageVolume(bars, start, lastIndex);
        BigDecimal latestVolume = BigDecimal.valueOf(latest.volume());

        if (latest.close().compareTo(priorHigh) <= 0 || averageVolume.signum() <= 0) return Optional.empty();
        BigDecimal volumeMultiple = latestVolume.divide(averageVolume, 8, RoundingMode.HALF_UP);
        if (volumeMultiple.compareTo(MIN_VOLUME_MULTIPLE) < 0) return Optional.empty();

        BigDecimal stop = latest.low().min(priorHigh).setScale(2, RoundingMode.HALF_UP);
        if (stop.signum() <= 0 || stop.compareTo(latest.close()) >= 0) return Optional.empty();
        BigDecimal risk = latest.close().subtract(stop);
        BigDecimal target = latest.close().add(risk.multiply(TWO)).setScale(2, RoundingMode.HALF_UP);

        double breakoutPercent = latest.close().subtract(priorHigh)
                .divide(priorHigh.max(new BigDecimal("0.01")), 8, RoundingMode.HALF_UP)
                .doubleValue();
        double volumeBoost = Math.min(0.35, Math.max(0.0, volumeMultiple.doubleValue() - 1.5) * 0.20);
        double score = IndicatorMath.clamp01(0.55 + Math.min(0.25, breakoutPercent * 10.0) + volumeBoost);

        return Optional.of(new ScanCandidate(
                context.instrumentId(), context.tradingSymbol(), context.exchange(), context.interval(), NAME,
                score, latest.close(), stop, target, latest.candleTime(),
                List.of("CLOSE_ABOVE_PRIOR_20_BAR_HIGH", "VOLUME_AT_LEAST_1_5X_AVERAGE", "RESEARCH_CANDIDATE_ONLY")));
    }
}
