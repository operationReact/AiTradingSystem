package in.aitradingsystem.marketdata;

import java.util.Arrays;
import java.util.Optional;

/**
 * Canonical source labels for F&O market observations. Every evaluator and
 * persistence path must use these wire labels rather than ad-hoc literals.
 *
 * <p>UPSTOX_V3_LIVE: exchange-timed L1 from the Upstox V3 websocket (quotes).
 * UPSTOX_ANALYTICS: Upstox REST analytics (historical candles with OI/volume, option chain).
 * ANGEL_ONE_SMARTAPI: Angel One SmartAPI market data (quotes and history).
 */
public enum FnoMarketDataProvider {
    UPSTOX_V3_LIVE("UPSTOX_V3"),
    UPSTOX_ANALYTICS("UPSTOX_ANALYTICS"),
    ANGEL_ONE_SMARTAPI("ANGEL_ONE_SMARTAPI");

    private final String wireLabel;

    FnoMarketDataProvider(String wireLabel) {
        this.wireLabel = wireLabel;
    }

    public String wireLabel() {
        return wireLabel;
    }

    public static Optional<FnoMarketDataProvider> fromWire(String label) {
        if (label == null) return Optional.empty();
        return Arrays.stream(values()).filter(p -> p.wireLabel.equals(label)).findFirst();
    }

    /** Exchange-timed L1 bid/ask quote sources (STR-170/171 quote legs). */
    public static boolean isQuoteSource(String label) {
        return fromWire(label).map(p -> p == UPSTOX_V3_LIVE || p == ANGEL_ONE_SMARTAPI).orElse(false);
    }

    /** Session-complete OI/volume history sources (STR-172 closing snapshots). */
    public static boolean isSessionHistorySource(String label) {
        return fromWire(label).map(p -> p == UPSTOX_ANALYTICS || p == ANGEL_ONE_SMARTAPI).orElse(false);
    }
}
