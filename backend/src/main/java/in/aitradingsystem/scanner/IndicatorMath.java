package in.aitradingsystem.scanner;

import in.aitradingsystem.marketdata.MarketCandle;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;

final class IndicatorMath {
    private IndicatorMath() {}

    static BigDecimal averageClose(List<MarketCandle> candles, int startInclusive, int endExclusive) {
        BigDecimal total = BigDecimal.ZERO;
        for (int i = startInclusive; i < endExclusive; i++) total = total.add(candles.get(i).close());
        return total.divide(BigDecimal.valueOf(endExclusive - startInclusive), 8, RoundingMode.HALF_UP);
    }

    static BigDecimal averageVolume(List<MarketCandle> candles, int startInclusive, int endExclusive) {
        BigDecimal total = BigDecimal.ZERO;
        for (int i = startInclusive; i < endExclusive; i++) total = total.add(BigDecimal.valueOf(candles.get(i).volume()));
        return total.divide(BigDecimal.valueOf(endExclusive - startInclusive), 8, RoundingMode.HALF_UP);
    }

    static BigDecimal highestHigh(List<MarketCandle> candles, int startInclusive, int endExclusive) {
        BigDecimal highest = candles.get(startInclusive).high();
        for (int i = startInclusive + 1; i < endExclusive; i++) highest = highest.max(candles.get(i).high());
        return highest;
    }

    static BigDecimal lowestLow(List<MarketCandle> candles, int startInclusive, int endExclusive) {
        BigDecimal lowest = candles.get(startInclusive).low();
        for (int i = startInclusive + 1; i < endExclusive; i++) lowest = lowest.min(candles.get(i).low());
        return lowest;
    }

    static double clamp01(double value) {
        return Math.max(0.0, Math.min(1.0, value));
    }
}
