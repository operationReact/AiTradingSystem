package in.aitradingsystem.marketdata;

import in.aitradingsystem.instrument.Instrument;
import java.math.BigDecimal;
import java.util.List;

/**
 * Broker-neutral, read-only market-data boundary.
 * Execution/order authority is deliberately not part of this contract.
 */
public interface MarketDataProvider {
    String source();

    /** Caller resolves the canonical instrument; no user-supplied broker tokens are trusted. */
    BigDecimal lastTradedPrice(Instrument instrument);

    /** Return validated, normalized candles, never a broker-specific JSON payload. */
    List<HistoricalCandleParser.CandleValues> historicalCandles(
            String exchange, String symbolToken, String interval, String fromDate, String toDate);
}
