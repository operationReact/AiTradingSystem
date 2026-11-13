package in.aitradingsystem.provider.upstox;

import in.aitradingsystem.instrument.Instrument;
import in.aitradingsystem.marketdata.HistoricalCandleInterval;
import in.aitradingsystem.marketdata.MarketCandle;
import in.aitradingsystem.scanner.ScanCandidate;
import in.aitradingsystem.scanner.ScanContext;
import in.aitradingsystem.scanner.ScannerEngine;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * In-memory, read-only adapter from Upstox Analytics candles to MintTick's
 * existing deterministic research scanner. Never persists provider data,
 * publishes strategy signals, places paper orders or authorizes live trading.
 *
 * Instrument-to-ISIN association is supplied by the caller and is NOT
 * independently verified here. Any candidate is research-only until the
 * source-rights, listing-identity, quality and paper-execution gates pass.
 */
public final class UpstoxResearchScanBridge {
    public static final String SOURCE = "UPSTOX_ANALYTICS_READONLY";
    private static final int MAX_BARS = 1024;
    private final ScannerEngine engine;

    public UpstoxResearchScanBridge(ScannerEngine engine) {
        this.engine = Objects.requireNonNull(engine, "scanner engine required");
    }

    public record Preview(String source, String instrumentKey, String interval,
                          Instant evaluatedAt, Instant latestCandleAt,
                          int validatedCandles, boolean instrumentMappingVerified,
                          boolean executionEnabled, List<ScanCandidate> candidates) {
        public Preview {
            candidates = List.copyOf(candidates);
        }
    }

    /**
     * Produces a provisional research-only preview from one vetted, chronological
     * Upstox candle series. No model training, order placement or storage.
     */
    public Preview preview(Instrument instrument, String instrumentKey,
                           HistoricalCandleInterval interval, Instant evaluatedAt,
                           List<UpstoxAnalyticsSource.Candle> upstoxCandles) {
        if (instrument == null || instrument.id() <= 0
                || instrument.tradingSymbol() == null || instrument.tradingSymbol().isBlank()
                || evaluatedAt == null || interval == null
                || !supported(interval) || upstoxCandles == null
                || upstoxCandles.isEmpty() || upstoxCandles.size() > MAX_BARS
                || instrument.exchange() == null
                || !("NSE".equals(instrument.exchange()) || "BSE".equals(instrument.exchange()))
                || !("EQ".equalsIgnoreCase(instrument.instrumentType())
                    || "STOCK".equalsIgnoreCase(instrument.instrumentType())
                    || "EQUITY".equalsIgnoreCase(instrument.instrumentType()))
                || instrumentKey == null
                || !instrumentKey.matches(("NSE".equals(instrument.exchange())
                    ? "NSE_EQ" : "BSE_EQ") + "\\|[A-Z0-9]{12}")) {
            throw new IllegalArgumentException("Invalid research-only Upstox equity mapping or interval");
        }

        ArrayList<MarketCandle> mapped = new ArrayList<>(upstoxCandles.size());
        Instant prior = null;
        for (UpstoxAnalyticsSource.Candle bar : upstoxCandles) {
            if (bar == null || bar.time() == null || bar.time().isAfter(evaluatedAt)
                    || (prior != null && !bar.time().isAfter(prior))
                    || !positive(bar.open()) || !positive(bar.high())
                    || !positive(bar.low()) || !positive(bar.close())
                    || bar.high().compareTo(bar.open()) < 0
                    || bar.high().compareTo(bar.close()) < 0
                    || bar.high().compareTo(bar.low()) < 0
                    || bar.low().compareTo(bar.open()) > 0
                    || bar.low().compareTo(bar.close()) > 0
                    || bar.volume() < 0 || bar.openInterest() < 0) {
                throw new IllegalArgumentException("Invalid, duplicate, future or unordered Upstox research candle");
            }
            prior = bar.time();
            mapped.add(new MarketCandle(0, instrument.id(), instrument.exchange(),
                    instrument.tradingSymbol(), instrument.symbolToken(),
                    instrument.catalogueVersion(), interval.name(), bar.time(),
                    bar.open(), bar.high(), bar.low(), bar.close(), bar.volume(),
                    SOURCE, evaluatedAt));
        }
        ScanContext context = new ScanContext(instrument.id(), instrument.tradingSymbol(),
                instrument.exchange(), interval.name(), evaluatedAt, mapped);
        // Existing ScannerEngine checks freshness and structural quality.
        // No candidate becomes a paper order through this bridge.
        List<ScanCandidate> candidates = engine.scan(context);
        return new Preview(SOURCE, instrumentKey, interval.name(), evaluatedAt,
                prior, mapped.size(), false, false, candidates);
    }

    private static boolean supported(HistoricalCandleInterval interval) {
        return switch (interval) {
            case ONE_MINUTE, THREE_MINUTE, FIVE_MINUTE, TEN_MINUTE,
                 FIFTEEN_MINUTE, THIRTY_MINUTE, ONE_HOUR -> true;
            case ONE_DAY -> false; // Existing scanner strategies are intraday.
        };
    }

    private static boolean positive(BigDecimal value) {
        return value != null && value.signum() > 0;
    }
}
