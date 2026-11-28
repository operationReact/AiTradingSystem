package in.aitradingsystem.scanner;

import in.aitradingsystem.marketdata.MarketCandle;
import java.util.List;

/**
 * Deterministic daily-swing indicator helpers. Every method is a pure function over completed,
 * chronological candles and reads only bars at or before the requested index, so it can never see
 * the future. These back the {@link TradingStyle#SWING} strategies and are intentionally parameter
 * driven so each catalog entry supplies its own explicit, versioned configuration.
 */
final class SwingIndicators {
    private SwingIndicators() {}

    /** Simple moving average of close over [endIndexInclusive-period+1, endIndexInclusive]. */
    static double sma(List<MarketCandle> bars, int endIndexInclusive, int period) {
        int start = endIndexInclusive - period + 1;
        if (start < 0 || period <= 0) throw new IllegalArgumentException("insufficient bars for sma");
        double total = 0.0;
        for (int i = start; i <= endIndexInclusive; i++) total += bars.get(i).close().doubleValue();
        return total / period;
    }

    /** Population standard deviation of close over the same window used by {@link #sma}. */
    static double stdDev(List<MarketCandle> bars, int endIndexInclusive, int period) {
        int start = endIndexInclusive - period + 1;
        if (start < 0 || period <= 0) throw new IllegalArgumentException("insufficient bars for stdDev");
        double mean = sma(bars, endIndexInclusive, period);
        double acc = 0.0;
        for (int i = start; i <= endIndexInclusive; i++) {
            double d = bars.get(i).close().doubleValue() - mean;
            acc += d * d;
        }
        return Math.sqrt(acc / period);
    }

    /**
     * Exponential moving average of close evaluated at {@code endIndexInclusive}. Seeded with the
     * simple average of the first {@code period} closes, then smoothed with k = 2/(period+1).
     */
    static double ema(List<MarketCandle> bars, int endIndexInclusive, int period) {
        if (period <= 0 || endIndexInclusive < period - 1) throw new IllegalArgumentException("insufficient bars for ema");
        double k = 2.0 / (period + 1.0);
        double seed = 0.0;
        for (int i = 0; i < period; i++) seed += bars.get(i).close().doubleValue();
        double ema = seed / period;
        for (int i = period; i <= endIndexInclusive; i++) {
            ema = bars.get(i).close().doubleValue() * k + ema * (1.0 - k);
        }
        return ema;
    }

    /** Wilder RSI at {@code endIndexInclusive}. Returns 50 when there is no movement at all. */
    static double rsi(List<MarketCandle> bars, int endIndexInclusive, int period) {
        if (period <= 0 || endIndexInclusive - period < 0) throw new IllegalArgumentException("insufficient bars for rsi");
        // Seed average gain/loss over the first `period` changes (indices 1..period).
        double gain = 0.0;
        double loss = 0.0;
        for (int i = 1; i <= period; i++) {
            double change = bars.get(i).close().doubleValue() - bars.get(i - 1).close().doubleValue();
            if (change >= 0) gain += change; else loss -= change;
        }
        double avgGain = gain / period;
        double avgLoss = loss / period;
        for (int i = period + 1; i <= endIndexInclusive; i++) {
            double change = bars.get(i).close().doubleValue() - bars.get(i - 1).close().doubleValue();
            double g = change >= 0 ? change : 0.0;
            double l = change < 0 ? -change : 0.0;
            avgGain = (avgGain * (period - 1) + g) / period;
            avgLoss = (avgLoss * (period - 1) + l) / period;
        }
        if (avgLoss == 0.0) return avgGain == 0.0 ? 50.0 : 100.0;
        double rs = avgGain / avgLoss;
        return 100.0 - (100.0 / (1.0 + rs));
    }

    /** Wilder ATR at {@code endIndexInclusive} over {@code period} bars. */
    static double atr(List<MarketCandle> bars, int endIndexInclusive, int period) {
        if (period <= 0 || endIndexInclusive - period < 0) throw new IllegalArgumentException("insufficient bars for atr");
        double seed = 0.0;
        for (int i = 1; i <= period; i++) seed += trueRange(bars, i);
        double atr = seed / period;
        for (int i = period + 1; i <= endIndexInclusive; i++) {
            atr = (atr * (period - 1) + trueRange(bars, i)) / period;
        }
        return atr;
    }

    private static double trueRange(List<MarketCandle> bars, int i) {
        double high = bars.get(i).high().doubleValue();
        double low = bars.get(i).low().doubleValue();
        double prevClose = bars.get(i - 1).close().doubleValue();
        return Math.max(high - low, Math.max(Math.abs(high - prevClose), Math.abs(low - prevClose)));
    }

    /** MACD line (EMA fast - EMA slow) at {@code endIndexInclusive}. */
    static double macdLine(List<MarketCandle> bars, int endIndexInclusive, int fast, int slow) {
        return ema(bars, endIndexInclusive, fast) - ema(bars, endIndexInclusive, slow);
    }

    /** MACD signal line: EMA({@code signalPeriod}) of the MACD line series ending at {@code endIndexInclusive}. */
    static double macdSignal(List<MarketCandle> bars, int endIndexInclusive, int fast, int slow, int signalPeriod) {
        int firstMacd = slow - 1; // earliest index where the slow EMA is defined
        int count = endIndexInclusive - firstMacd + 1;
        if (count < signalPeriod) throw new IllegalArgumentException("insufficient bars for macd signal");
        double k = 2.0 / (signalPeriod + 1.0);
        double seed = 0.0;
        for (int j = 0; j < signalPeriod; j++) seed += macdLine(bars, firstMacd + j, fast, slow);
        double signal = seed / signalPeriod;
        for (int idx = firstMacd + signalPeriod; idx <= endIndexInclusive; idx++) {
            signal = macdLine(bars, idx, fast, slow) * k + signal * (1.0 - k);
        }
        return signal;
    }
}
