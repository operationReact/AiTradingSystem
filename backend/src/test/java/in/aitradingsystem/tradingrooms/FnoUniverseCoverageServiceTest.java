package in.aitradingsystem.tradingrooms;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import in.aitradingsystem.marketdata.MarketSessionCalendar;
import in.aitradingsystem.provider.upstox.UpstoxFnoInstrumentUniverse;
import in.aitradingsystem.provider.upstox.UpstoxFnoV3LiveFeedService;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

class FnoUniverseCoverageServiceTest {
    private static final String KEY = "NSE_FO|61001";
    private static final Instant NOW = Instant.parse("2026-11-23T08:03:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
    private final FnoFuturesContractRegistry registry =
        mock(FnoFuturesContractRegistry.class);
    private final MarketSessionCalendar calendar = mock(MarketSessionCalendar.class);
    @SuppressWarnings("unchecked")
    private final ObjectProvider<UpstoxFnoV3LiveFeedService> provider =
        mock(ObjectProvider.class);
    private final UpstoxFnoV3LiveFeedService socket =
        mock(UpstoxFnoV3LiveFeedService.class);

    private static UpstoxFnoInstrumentUniverse.Contract contract() {
        return new UpstoxFnoInstrumentUniverse.Contract(
            KEY, "NSE_INDEX|Nifty 50", "NIFTY", "NIFTY 26000 CE",
            "CE", LocalDate.of(2026, 12, 1),
            new BigDecimal("26000"), 65, new BigDecimal("5"),
            new BigDecimal("100000"), true);
    }

    private FnoUniverseCoverageService subject(boolean rights) {
        return new FnoUniverseCoverageService(registry, provider, calendar, CLOCK, rights);
    }

    private void listedAndConnected(boolean subscribed) {
        when(registry.listedInstrument(KEY)).thenReturn(Optional.of(contract()));
        when(calendar.isInsideFnoSession(NOW)).thenReturn(true);
        when(provider.getIfAvailable()).thenReturn(socket);
        when(socket.status()).thenReturn(new UpstoxFnoV3LiveFeedService.Status(
            "CONNECTED", 64, 50, 1000, 0, NOW, NOW, 0,
            true, false, false, false, 0, null));
        when(socket.hasActiveFnoSubscription(KEY)).thenReturn(subscribed);
    }

    @Test
    void absentCatalogNeverEntersFeedPath() {
        when(registry.universeStatus()).thenReturn(
            new FnoFuturesContractRegistry.UniverseStatus(
                "NOT_LOADED", null, null, null, 0, 0, 0, 0, 0));
        var evidence = subject(true).inspect(KEY);
        assertEquals("CATALOG_UNAVAILABLE", evidence.state());
        assertFalse(evidence.orderAuthorized());
        verifyNoInteractions(provider, socket, calendar);
    }

    @Test
    void rightsGateBlocksBeforeSocketEvenWithListedContract() {
        when(registry.listedInstrument(KEY)).thenReturn(Optional.of(contract()));
        var evidence = subject(false).inspect(KEY);
        assertEquals("DISPLAY_RIGHTS_NOT_VERIFIED", evidence.state());
        assertFalse(evidence.freshLtp());
        verifyNoInteractions(provider, socket, calendar);
    }

    @Test
    void sourceListedButNotSubscribedIsNotMistakenForFreshLiveData() {
        listedAndConnected(false);
        var evidence = subject(true).inspect(KEY);
        assertEquals("NOT_SUBSCRIBED", evidence.state());
        assertTrue(evidence.catalogListed());
        assertFalse(evidence.subscribed());
        assertFalse(evidence.freshLtp());
        verify(socket, never()).eligibleDisplayQuote(KEY);
    }

    @Test
    void subscribedWithoutFreshExchangeTimeBlocksMindRecommendation() {
        listedAndConnected(true);
        when(socket.eligibleDisplayQuote(KEY)).thenReturn(Optional.empty());
        var evidence = subject(true).inspect(KEY);
        assertEquals("SUBSCRIBED_NO_FRESH_TRADE", evidence.state());
        assertTrue(evidence.subscribed());
        assertFalse(evidence.freshLtp());
        assertFalse(evidence.depthQualified());
        verify(socket, never()).eligiblePaperQuote(KEY);
    }

    @Test
    void validLtpAndOiGreeksVolumeWithoutDepthIsNotAnOrderGrant() {
        listedAndConnected(true);
        Instant trade = NOW.minusSeconds(1);
        when(socket.eligibleDisplayQuote(KEY)).thenReturn(Optional.of(
            new UpstoxFnoV3LiveFeedService.DisplayQuote(
                KEY, new BigDecimal("122.5"), new BigDecimal("119"), 10000L,
                new BigDecimal(".21"), new BigDecimal(".4"),
                new BigDecimal("-2"), new BigDecimal(".001"),
                new BigDecimal("4"), null, null, trade, trade,
                120000L, null, null, 65L)));
        when(socket.eligiblePaperQuote(KEY)).thenReturn(Optional.empty());
        var evidence = subject(true).inspect(KEY);
        assertEquals("FRESH_V3_LTP_ONLY", evidence.state());
        assertTrue(evidence.freshLtp());
        assertFalse(evidence.depthQualified());
        assertTrue(evidence.hasOpenInterest());
        assertTrue(evidence.hasVolume());
        assertTrue(evidence.hasGreeks());
        assertEquals(trade, evidence.exchangeAt());
        assertFalse(evidence.orderAuthorized());
    }

    @Test
    void eligibleDepthStillCannotAuthorizeBrokerOrderWithoutAccountGate() {
        listedAndConnected(true);
        Instant trade = NOW.minusSeconds(1);
        when(socket.eligibleDisplayQuote(KEY)).thenReturn(Optional.of(
            new UpstoxFnoV3LiveFeedService.DisplayQuote(
                KEY, new BigDecimal("122.5"), null, null,
                null, null, null, null, null,
                new BigDecimal("122"), new BigDecimal("123"),
                trade, trade)));
        when(socket.eligiblePaperQuote(KEY)).thenReturn(Optional.of(
            new UpstoxFnoV3LiveFeedService.PaperQuote(
                KEY, new BigDecimal("122"), new BigDecimal("123"),
                trade, trade)));
        var evidence = subject(true).inspect(KEY);
        assertEquals("FRESH_V3_LTP_AND_DEPTH", evidence.state());
        assertTrue(evidence.depthQualified());
        assertTrue(evidence.freshLtp());
        assertFalse(evidence.orderAuthorized());
        assertFalse(evidence.hasGreeks());
    }

    @Test
    void invalidOrUnknownKeyCannotTriggerProviderRequests() {
        assertEquals("INVALID_INSTRUMENT_KEY",
            subject(true).inspect("NSE_EQ|INE002A01018").state());
        verifyNoInteractions(provider, socket, calendar);
    }
}
