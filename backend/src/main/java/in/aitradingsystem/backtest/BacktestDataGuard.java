package in.aitradingsystem.backtest;

import in.aitradingsystem.marketdata.MarketCandle;
import java.time.Instant;
import java.util.List;

/**
 * Central fail-closed guard for point-in-time backtest data access.
 *
 * <p>The guard rejects incomplete or non-strictly-ordered candle series and provides bounded
 * point-in-time views so strategy evaluation cannot observe a candle after the current simulation
 * index. Execution is deliberately resolved to the next observed candle; missing overnight/session
 * bars are never synthesized, so session gaps remain explicit and execution occurs at the next
 * available open.</p>
 */
public final class BacktestDataGuard {
    private BacktestDataGuard() {}

    public static List<MarketCandle> pointInTimeView(List<MarketCandle> candles, Instant cutoff) {
        if (cutoff == null) throw new IllegalArgumentException("cutoff is required");
        validateChronology(candles);
        for (MarketCandle candle : candles) {
            if (candle.candleTime().isAfter(cutoff)) {
                throw new IllegalArgumentException("future candles are not allowed");
            }
        }
        return List.copyOf(candles);
    }

    /** Returns an immutable history ending at the current simulation index, inclusive. */
    public static List<MarketCandle> historyThrough(List<MarketCandle> candles, int currentSimulationIndex) {
        validateChronology(candles);
        if (currentSimulationIndex < 0 || currentSimulationIndex >= candles.size()) {
            throw new IllegalArgumentException("currentSimulationIndex is outside candle history");
        }
        return List.copyOf(candles.subList(0, currentSimulationIndex + 1));
    }

    /**
     * Returns the next observed candle used for execution. This intentionally does not fabricate
     * candles across market/session gaps: the next real candle open is the execution point.
     */
    public static MarketCandle nextExecutionCandle(List<MarketCandle> candles, int signalIndex) {
        validateChronology(candles);
        if (signalIndex < 0 || signalIndex + 1 >= candles.size()) {
            throw new IllegalArgumentException("signal must have a following execution candle");
        }
        return candles.get(signalIndex + 1);
    }

    public static void validateChronology(List<MarketCandle> candles) {
        if (candles == null || candles.isEmpty()) {
            throw new IllegalArgumentException("Candle history is required");
        }
        Instant previous = null;
        for (MarketCandle candle : candles) {
            if (candle == null || candle.candleTime() == null || candle.open() == null || candle.high() == null
                    || candle.low() == null || candle.close() == null) {
                throw new IllegalArgumentException("Candle history contains incomplete rows");
            }
            if (previous != null && !candle.candleTime().isAfter(previous)) {
                throw new IllegalArgumentException("Candle history must be strictly chronological");
            }
            previous = candle.candleTime();
        }
    }
}
