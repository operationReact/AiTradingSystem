package in.aitradingsystem.tradingrooms;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import in.aitradingsystem.marketdata.MarketSessionCalendar;
import in.aitradingsystem.provider.upstox.UpstoxAnalyticsSource;
import in.aitradingsystem.provider.upstox.UpstoxFnoInstrumentUniverse;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class FnoUniverseLastTradeServiceTest {
    private static final Instant NOW = Instant.parse("2026-11-23T08:12:00Z");
    private static final String KEY = "NSE_FO|61001";
    private final FnoFuturesContractRegistry registry =
        mock(FnoFuturesContractRegistry.class);
    private final UpstoxAnalyticsSource source =
        mock(UpstoxAnalyticsSource.class);
    private final MarketSessionCalendar calendar = mock(MarketSessionCalendar.class);

    private FnoUniverseLastTradeService service(boolean display, boolean rest) {
        return new FnoUniverseLastTradeService(registry, source, calendar,
            Clock.fixed(NOW, ZoneOffset.UTC), display, rest);
    }

    private static UpstoxFnoInstrumentUniverse.Contract contract() {
        return new UpstoxFnoInstrumentUniverse.Contract(
            KEY, "NSE_INDEX|Nifty 50", "NIFTY", "NIFTY 26000 CE",
            "CE", LocalDate.of(2026, 12, 1),
            new BigDecimal("26000"), 65, new BigDecimal("5"),
            new BigDecimal("100000"), true);
    }

    private void authorized() {
        when(registry.listedInstrument(KEY)).thenReturn(Optional.of(contract()));
        when(source.enabled()).thenReturn(true);
        when(calendar.isInsideFnoSession(NOW)).thenReturn(true);
    }

    @Test
    void absentCatalogNeverCallsRestOrManufacturesPrices() {
        var result = service(true, true).read(KEY);
        assertEquals("INSTRUMENT_NOT_VERIFIED_IN_CURRENT_MASTER", result.status());
        assertNull(result.ltp());
        assertFalse(result.executable());
        verifyNoInteractions(source, calendar);
    }

    @Test
    void rightsDeniedDoesNotCallPrivateSourceEvenIfBodExists() {
        when(registry.listedInstrument(KEY)).thenReturn(Optional.of(contract()));
        var result = service(true, false).read(KEY);
        assertEquals("LAST_PRICE_DISPLAY_RIGHTS_NOT_VERIFIED", result.status());
        assertNull(result.ltp());
        verifyNoInteractions(source, calendar);
    }

    @Test
    void actualExchangeTimedCurrentLastPriceIsNeverAnExecutableBidAsk() {
        authorized();
        Instant trade = NOW.minusSeconds(2);
        when(source.listedFnoLastTrades(List.of(KEY))).thenReturn(Map.of(
            KEY, new UpstoxAnalyticsSource.ListedLastTrade(
                KEY, new BigDecimal("145.25"), new BigDecimal("130"),
                9000L, trade, NOW.minusSeconds(1))));
        var s = service(true, true);
        var result = s.read(KEY);
        assertEquals("SOURCE_LAST_TRADE", result.status());
        assertEquals("UPSTOX_REST_V3_LAST_TRADE", result.provenance());
        assertEquals(0, result.ltp().compareTo(new BigDecimal("145.25")));
        assertTrue(result.freshExchangeTime());
        assertFalse(result.executable());
        assertEquals(trade, result.exchangeAt());
        assertEquals(9000L, result.openInterest());
        // Repeated phone refreshes are served from the bounded 5-second
        // in-memory cache; no repeated vendor GET.
        assertEquals(result, s.read(KEY));
        verify(source, times(1)).listedFnoLastTrades(List.of(KEY));
    }

    @Test
    void oldMarketLastTradeShowsLastButNotFreshDuringMarket() {
        authorized();
        Instant trade = NOW.minusSeconds(3600);
        when(source.listedFnoLastTrades(List.of(KEY))).thenReturn(Map.of(
            KEY, new UpstoxAnalyticsSource.ListedLastTrade(
                KEY, new BigDecimal("110"), null, null, trade, NOW.minusSeconds(1))));
        var result = service(true, true).read(KEY);
        assertEquals("SOURCE_LAST_TRADE", result.status());
        assertEquals(new BigDecimal("110"), result.ltp());
        assertFalse(result.freshExchangeTime());
        assertFalse(result.executable());
        assertNull(result.openInterest());
    }

    @Test
    void rateCapDoesNotFetchTheThirtyFirstUnseenContract() {
        when(registry.listedInstrument(anyString())).thenReturn(Optional.of(contract()));
        when(source.enabled()).thenReturn(true);
        when(calendar.isInsideFnoSession(NOW)).thenReturn(true);
        when(source.listedFnoLastTrades(anyList())).thenReturn(Map.of());
        var service = service(true, true);
        for (int index = 1; index <= 30; index++) {
            assertEquals("LAST_TRADE_NOT_AVAILABLE",
                service.read("NSE_FO|" + index).status());
        }
        var blocked = service.read("NSE_FO|31");
        assertEquals("SOURCE_RATE_BUDGET_EXHAUSTED", blocked.status());
        assertFalse(blocked.executable());
        verify(source, times(30)).listedFnoLastTrades(anyList());
    }

    @Test
    void invalidInstrumentKeyFailsWithoutProviderContact() {
        var result = service(true, true).read("NSE_EQ|INE002A01018");
        assertEquals("INVALID_INSTRUMENT_KEY", result.status());
        assertNull(result.ltp());
        verifyNoInteractions(registry, source, calendar);
    }
}
