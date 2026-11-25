package in.aitradingsystem.scanner;

import in.aitradingsystem.marketdata.MarketCandle;
import java.time.Instant;
import java.util.List;

/** Immutable, replayable input for scanner strategies. Candles must be chronological. */
public record ScanContext(
        long instrumentId,
        String tradingSymbol,
        String exchange,
        String interval,
        Instant evaluatedAt,
        List<MarketCandle> candles) {

    public ScanContext {
        if (instrumentId < 1) throw new IllegalArgumentException("instrumentId must be positive");
        if (tradingSymbol == null || tradingSymbol.isBlank()) throw new IllegalArgumentException("tradingSymbol is required");
        if (exchange == null || exchange.isBlank()) throw new IllegalArgumentException("exchange is required");
        if (interval == null || interval.isBlank()) throw new IllegalArgumentException("interval is required");
        if (evaluatedAt == null) throw new IllegalArgumentException("evaluatedAt is required");
        candles = candles == null ? List.of() : List.copyOf(candles);
        Instant previous = null;
        for (MarketCandle candle : candles) {
            if (candle == null) throw new IllegalArgumentException("candles cannot contain null values");
            if (previous != null && candle.candleTime().isBefore(previous)) {
                throw new IllegalArgumentException("candles must be chronological");
            }
            previous = candle.candleTime();
        }
    }
}
