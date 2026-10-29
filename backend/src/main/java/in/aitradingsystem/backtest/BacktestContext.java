package in.aitradingsystem.backtest;

import in.aitradingsystem.marketdata.MarketCandle;
import in.aitradingsystem.scanner.StrategyConfigSnapshot;
import java.time.Instant;
import java.util.List;

/** Immutable point-in-time view presented to a strategy. Future candles are rejected. */
public record BacktestContext(
        long instrumentId,
        String tradingSymbol,
        String exchange,
        String interval,
        Instant cutoff,
        List<MarketCandle> candles,
        StrategyConfigSnapshot configSnapshot,
        BacktestAccountState accountState) {

    public BacktestContext {
        if (instrumentId < 1) throw new IllegalArgumentException("instrumentId must be positive");
        if (tradingSymbol == null || tradingSymbol.isBlank()) throw new IllegalArgumentException("tradingSymbol is required");
        if (exchange == null || exchange.isBlank()) throw new IllegalArgumentException("exchange is required");
        if (interval == null || interval.isBlank()) throw new IllegalArgumentException("interval is required");
        if (cutoff == null) throw new IllegalArgumentException("cutoff is required");
        if (accountState == null) throw new IllegalArgumentException("accountState is required");
        candles = candles == null ? List.of() : List.copyOf(candles);
        Instant previous = null;
        for (MarketCandle candle : candles) {
            if (candle == null) throw new IllegalArgumentException("candles cannot contain null values");
            if (candle.candleTime().isAfter(cutoff)) throw new IllegalArgumentException("future candles are not allowed");
            if (previous != null && candle.candleTime().isBefore(previous)) {
                throw new IllegalArgumentException("candles must be chronological");
            }
            previous = candle.candleTime();
        }
    }
}
