package in.aitradingsystem.research.simulation;

import in.aitradingsystem.marketdata.MarketCandle;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Point-in-time regime classifier.
 * Volatility methodology: population standard deviation of simple close-to-close returns.
 * The current 20-candle window is classified against the 33rd/67th percentiles of all eligible
 * rolling 20-candle volatility windows ending on or before the supplied data cutoff.
 * Liquidity is secondary evidence only: current 20-candle average volume divided by the median
 * rolling-window average volume; <0.80=LOW, >1.20=HIGH, otherwise NORMAL.
 */
public final class RegimeClassifier {
    public static final int DEFAULT_WINDOW = 20;
    public static final String METHODOLOGY = "SIMPLE_RETURN_VOL_P33_P67_REL_VOLUME_V1";
    private static final double LOW_VOLUME_RATIO = 0.80d;
    private static final double HIGH_VOLUME_RATIO = 1.20d;

    public RegimeSnapshot classify(List<MarketCandle> candles, Instant dataCutoffAt) {
        return classify(candles, dataCutoffAt, DEFAULT_WINDOW);
    }

    RegimeSnapshot classify(List<MarketCandle> candles, Instant dataCutoffAt, int window) {
        if (candles == null || dataCutoffAt == null) throw new IllegalArgumentException("candles and dataCutoffAt are required");
        if (window < 3) throw new IllegalArgumentException("window must be at least 3 candles");

        List<MarketCandle> eligible = candles.stream()
                .filter(c -> c != null && c.candleTime() != null && !c.candleTime().isAfter(dataCutoffAt))
                .sorted(Comparator.comparing(MarketCandle::candleTime))
                .toList();
        if (eligible.size() < window + 2) {
            throw new IllegalArgumentException("insufficient completed candles for regime classification");
        }
        for (int i = 0; i < eligible.size(); i++) {
            MarketCandle candle = eligible.get(i);
            if (candle.close() == null || candle.close().signum() <= 0) {
                throw new IllegalArgumentException("completed candles require positive close prices");
            }
            if (i > 0 && !eligible.get(i - 1).candleTime().isBefore(candle.candleTime())) {
                throw new IllegalArgumentException("completed candles must be strictly chronological");
            }
        }

        List<Double> volWindows = new ArrayList<>();
        List<Double> volumeWindows = new ArrayList<>();
        for (int endExclusive = window; endExclusive <= eligible.size(); endExclusive++) {
            List<MarketCandle> slice = eligible.subList(endExclusive - window, endExclusive);
            volWindows.add(realizedVolatility(slice));
            volumeWindows.add(averageVolume(slice));
        }

        double currentVol = volWindows.get(volWindows.size() - 1);
        double lower = percentile(volWindows, 0.33d);
        double upper = percentile(volWindows, 0.67d);
        MarketRegime marketRegime = currentVol < lower ? MarketRegime.LOW_VOL
                : currentVol > upper ? MarketRegime.HIGH_VOL : MarketRegime.NORMAL_VOL;

        double historicalMedianVolume = percentile(volumeWindows, 0.50d);
        double currentAverageVolume = volumeWindows.get(volumeWindows.size() - 1);
        double relativeVolume = historicalMedianVolume <= 0 ? 1.0d : currentAverageVolume / historicalMedianVolume;
        LiquidityRegime liquidity = relativeVolume < LOW_VOLUME_RATIO ? LiquidityRegime.LOW
                : relativeVolume > HIGH_VOLUME_RATIO ? LiquidityRegime.HIGH : LiquidityRegime.NORMAL;

        return new RegimeSnapshot(currentVol, lower, upper, marketRegime, relativeVolume, liquidity, METHODOLOGY);
    }

    static double realizedVolatility(List<MarketCandle> window) {
        List<Double> returns = new ArrayList<>(window.size() - 1);
        for (int i = 1; i < window.size(); i++) {
            double previous = window.get(i - 1).close().doubleValue();
            double current = window.get(i).close().doubleValue();
            returns.add(current / previous - 1.0d);
        }
        double mean = returns.stream().mapToDouble(Double::doubleValue).average().orElse(0.0d);
        double variance = returns.stream().mapToDouble(value -> {
            double difference = value - mean;
            return difference * difference;
        }).average().orElse(0.0d);
        return Math.sqrt(variance);
    }

    private static double averageVolume(List<MarketCandle> window) {
        return window.stream().mapToLong(MarketCandle::volume).average().orElse(0.0d);
    }

    static double percentile(List<Double> values, double quantile) {
        List<Double> sorted = values.stream().sorted().toList();
        if (sorted.isEmpty()) throw new IllegalArgumentException("percentile requires values");
        if (sorted.size() == 1) return sorted.get(0);
        double position = (sorted.size() - 1) * quantile;
        int lower = (int) Math.floor(position);
        int upper = (int) Math.ceil(position);
        if (lower == upper) return sorted.get(lower);
        double fraction = position - lower;
        return sorted.get(lower) + (sorted.get(upper) - sorted.get(lower)) * fraction;
    }
}
