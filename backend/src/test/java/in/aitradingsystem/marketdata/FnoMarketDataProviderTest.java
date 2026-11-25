package in.aitradingsystem.marketdata;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Optional;
import org.junit.jupiter.api.Test;

class FnoMarketDataProviderTest {
    @Test
    void wireLabelsAreStableCanonicalStrings() {
        assertEquals("UPSTOX_V3", FnoMarketDataProvider.UPSTOX_V3_LIVE.wireLabel());
        assertEquals("UPSTOX_ANALYTICS", FnoMarketDataProvider.UPSTOX_ANALYTICS.wireLabel());
        assertEquals("ANGEL_ONE_SMARTAPI", FnoMarketDataProvider.ANGEL_ONE_SMARTAPI.wireLabel());
    }

    @Test
    void fromWireAcceptsOnlyExactCanonicalLabels() {
        assertEquals(Optional.of(FnoMarketDataProvider.UPSTOX_V3_LIVE),
            FnoMarketDataProvider.fromWire("UPSTOX_V3"));
        assertTrue(FnoMarketDataProvider.fromWire(null).isEmpty());
        assertTrue(FnoMarketDataProvider.fromWire("upstox_v3").isEmpty());
        assertTrue(FnoMarketDataProvider.fromWire(" UPSTOX_V3").isEmpty());
        assertTrue(FnoMarketDataProvider.fromWire("UPSTOX_V3_LIVE").isEmpty());
    }

    @Test
    void quoteSourcesExcludeRestAnalyticsHistory() {
        assertTrue(FnoMarketDataProvider.isQuoteSource("UPSTOX_V3"));
        assertTrue(FnoMarketDataProvider.isQuoteSource("ANGEL_ONE_SMARTAPI"));
        assertFalse(FnoMarketDataProvider.isQuoteSource("UPSTOX_ANALYTICS"));
        assertFalse(FnoMarketDataProvider.isQuoteSource(null));
    }

    @Test
    void sessionHistorySourcesExcludeLiveWebsocketLabel() {
        assertTrue(FnoMarketDataProvider.isSessionHistorySource("UPSTOX_ANALYTICS"));
        assertTrue(FnoMarketDataProvider.isSessionHistorySource("ANGEL_ONE_SMARTAPI"));
        assertFalse(FnoMarketDataProvider.isSessionHistorySource("UPSTOX_V3"));
        assertFalse(FnoMarketDataProvider.isSessionHistorySource("unknown"));
    }
}
