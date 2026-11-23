package in.aitradingsystem.provider.upstox;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import in.aitradingsystem.marketdata.MarketSessionCalendar;
import in.aitradingsystem.tradingrooms.FnoFuturesContractRegistry;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Deterministic authorisation and quote-source validation, no broker network. */
class UpstoxFnoAutoWatchlistExpansionTest {
    private static final String NIFTY = "NSE_INDEX|Nifty 50";
    private static final Instant NOW = Instant.parse("2026-11-23T08:03:00Z");
    private static final LocalDate EXPIRY = LocalDate.parse("2026-12-01");
    private final UpstoxAnalyticsSource source = mock(UpstoxAnalyticsSource.class);
    private final FnoFuturesContractRegistry catalog =
        mock(FnoFuturesContractRegistry.class);
    private final UpstoxFnoV3LiveFeedService feed =
        mock(UpstoxFnoV3LiveFeedService.class);
    private final MarketSessionCalendar calendar =
        mock(MarketSessionCalendar.class);

    private UpstoxFnoAutoWatchlistExpansion subject(boolean display, String configured) {
        return new UpstoxFnoAutoWatchlistExpansion(
            source, catalog, feed, calendar,
            Clock.fixed(NOW, ZoneOffset.UTC), display, configured);
    }

    private static UpstoxFnoV3LiveFeedService.Status status(String state, int n) {
        return new UpstoxFnoV3LiveFeedService.Status(
            state, n, n, 100, 0, NOW, NOW, 0,
            true, false, false, false, 0, null);
    }

    private static UpstoxFnoInstrumentUniverse.Contract c(String key, String type,
            int strike) {
        return new UpstoxFnoInstrumentUniverse.Contract(
            key, NIFTY, "NIFTY", "NIFTY " + type, type, EXPIRY,
            BigDecimal.valueOf(strike), 65, BigDecimal.valueOf(5), null, true);
    }

    private void prepared() {
        when(feed.subscriptionLimit()).thenReturn(256);
        when(source.enabled()).thenReturn(true);
        when(calendar.isInsideFnoSession(NOW)).thenReturn(true);
        when(feed.status()).thenReturn(status("CONNECTED", 64));
        when(catalog.universeStatus()).thenReturn(
            new FnoFuturesContractRegistry.UniverseStatus(
                "LOADED", null, NOW.minusSeconds(300), "official-digest",
                45451, 667, 22273, 22511, 224));
        when(catalog.livePlanningCandidates(NIFTY)).thenReturn(
            List.of(c("NSE_FO|101", "FUT", 0),
                c("NSE_FO|201", "CE", 25600),
                c("NSE_FO|202", "PE", 25600)));
        var call = new UpstoxAnalyticsSource.FnoOptionQuote(
            "NSE_FO|201", new BigDecimal("100"),
            new BigDecimal("99"), new BigDecimal("101"),
            100L, 1000L, 950L, null, null, null, null, null);
        var put = new UpstoxAnalyticsSource.FnoOptionQuote(
            "NSE_FO|202", new BigDecimal("120"),
            new BigDecimal("119"), new BigDecimal("121"),
            100L, 900L, 850L, null, null, null, null, null);
        var row = new UpstoxAnalyticsSource.FnoOptionStrike(
            EXPIRY, NIFTY, new BigDecimal("25600"),
            new BigDecimal("25625"), call, put);
        when(source.currentFnoOptionChain(NIFTY, EXPIRY)).thenReturn(
            new UpstoxAnalyticsSource.OptionChainSnapshot(
                "UPSTOX_ANALYTICS", NOW, false, List.of(row)));
        when(feed.subscribeAdditionalListed(anyList()))
            .thenReturn(status("CONNECTED", 67));
    }

    @Test
    void operatorApprovedWatchlistAutoFillsOnlyCurrentConnectedSourceKeys() {
        prepared();
        var auto = subject(true, NIFTY);
        auto.reconcile();
        assertThat(auto.status().state()).isEqualTo(
            "ADD_SENT_AWAITING_EXCHANGE_TICKS");
        assertThat(auto.status().liveTradingEnabled()).isFalse();
        assertThat(auto.status().nextUnderlyingIndex()).isEqualTo(1);
        verify(feed).subscribeAdditionalListed(
            List.of("NSE_FO|101", "NSE_FO|201", "NSE_FO|202"));
        verify(feed, never()).start(anyList());
        verify(feed, never()).stop();
        verify(source, never()).authorizeFnoMarketFeedV3();
        assertThat(auto.status().nextAttemptAt()).isAfter(NOW);
        // A source send is not claimed as a fresh market observation.
        verify(feed, never()).eligibleDisplayQuote(anyString());
    }

    @Test
    void noConfigurationOrMalformedWatchlistNeverTouchesSource() {
        var absent = subject(true, "");
        absent.reconcile();
        assertThat(absent.status().state()).isEqualTo(
            "WATCHLIST_NOT_CONFIGURED_OR_INVALID");
        var injected = subject(true, "NSE_FO|arbitrary");
        injected.reconcile();
        assertThat(injected.status().state()).isEqualTo(
            "WATCHLIST_NOT_CONFIGURED_OR_INVALID");
        var duplicate = subject(true, NIFTY + ";" + NIFTY);
        duplicate.reconcile();
        assertThat(duplicate.status().configuredUnderlyings()).isZero();
        verifyNoInteractions(source, feed, calendar, catalog);
    }

    @Test
    void noDisplayRightsOrClosedExchangeCannotSendSubscriptionCommands() {
        prepared();
        var denied = subject(false, NIFTY);
        denied.reconcile();
        assertThat(denied.status().state()).isEqualTo(
            "DISPLAY_OR_SOURCE_NOT_VERIFIED");
        verify(feed, never()).subscribeAdditionalListed(anyList());
        var closed = subject(true, NIFTY);
        when(calendar.isInsideFnoSession(NOW)).thenReturn(false);
        closed.reconcile();
        assertThat(closed.status().state()).isEqualTo("MARKET_CLOSED");
        verify(feed, never()).subscribeAdditionalListed(anyList());
    }

    @Test
    void disconnectedOrFullSocketNeverPreemptedOrRestarted() {
        prepared();
        when(feed.status()).thenReturn(status("RECONNECTING", 64));
        var disconnected = subject(true, NIFTY);
        disconnected.reconcile();
        assertThat(disconnected.status().state()).isEqualTo(
            "WAITING_FOR_CONNECTED_SOCKET");
        when(feed.status()).thenReturn(status("CONNECTED", 256));
        var full = subject(true, NIFTY);
        full.reconcile();
        assertThat(full.status().state()).isEqualTo("CAPACITY_REACHED");
        verify(feed, never()).stop();
        verify(feed, never()).start(anyList());
        verify(feed, never()).subscribeAdditionalListed(anyList());
        verify(source, never()).currentFnoOptionChain(anyString(), any());
    }

    @Test
    void expiredOrMissingOfficialMasterStopsBeforeProviderChainCall() {
        prepared();
        when(catalog.universeStatus()).thenReturn(
            new FnoFuturesContractRegistry.UniverseStatus(
                "STALE_SNAPSHOT", null, NOW.minusSeconds(150000),
                "old-digest", 45451, 667, 22273, 22511, 224));
        var stale = subject(true, NIFTY);
        stale.reconcile();
        assertThat(stale.status().state()).isEqualTo("BOD_CATALOG_UNVERIFIED");
        verify(feed, never()).subscribeAdditionalListed(anyList());
        verify(source, never()).currentFnoOptionChain(anyString(), any());
    }

    @Test
    void providerFailureLeavesCurrentSocketUntouchedAndSchedulesBackoff() {
        prepared();
        when(source.currentFnoOptionChain(NIFTY, EXPIRY))
            .thenThrow(new IllegalStateException("sensitive provider error"));
        var auto = subject(true, NIFTY);
        auto.reconcile();
        assertThat(auto.status().state()).isEqualTo(
            "PROVIDER_OR_SOCKET_UNAVAILABLE");
        assertThat(auto.status().nextAttemptAt()).isAfter(NOW.plusSeconds(60));
        assertThat(auto.status().liveTradingEnabled()).isFalse();
        verify(feed, never()).subscribeAdditionalListed(anyList());
        verify(feed, never()).stop();
    }
}
