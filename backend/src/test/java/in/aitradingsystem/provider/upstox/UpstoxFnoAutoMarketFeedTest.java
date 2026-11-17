package in.aitradingsystem.provider.upstox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import in.aitradingsystem.marketdata.MarketSessionCalendar;
import in.aitradingsystem.tradingrooms.CustomerFnoExpiryCatalog;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.Test;

class UpstoxFnoAutoMarketFeedTest {
    private static final Instant MARKET = Instant.parse("2026-11-18T05:00:00Z");
    private static final LocalDate EXPIRY = LocalDate.parse("2026-11-24");
    private static final String UNDERLYING = "NSE_INDEX|Nifty 50";
    private final UpstoxAnalyticsSource source = mock(UpstoxAnalyticsSource.class);
    private final UpstoxFnoV3LiveFeedService service = mock(UpstoxFnoV3LiveFeedService.class);
    private final MarketSessionCalendar calendar = mock(MarketSessionCalendar.class);
    private final CustomerFnoExpiryCatalog catalog = mock(CustomerFnoExpiryCatalog.class);
    private final MutableClock clock = new MutableClock(MARKET);

    static final class MutableClock extends Clock {
        Instant value;
        MutableClock(Instant value) { this.value = value; }
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return value; }
    }

    private static UpstoxFnoV3LiveFeedService.Status status(String state) {
        return new UpstoxFnoV3LiveFeedService.Status(
            state, 0, 0, 0, 0, null, null, 0,
            false, false, false, false, 0, null);
    }

    private UpstoxFnoAutoMarketFeed manager(boolean rights) {
        return new UpstoxFnoAutoMarketFeed(source, service, catalog,
                calendar, clock, rights);
    }

    private void eligibleSource() {
        when(source.enabled()).thenReturn(true);
        when(calendar.isInsideFnoSession(any(Instant.class))).thenReturn(true);
        when(service.status()).thenReturn(status("STOPPED"));
        when(catalog.listing(any(Instant.class))).thenReturn(
            new CustomerFnoExpiryCatalog.Listing(
                "VERIFIED", MARKET, List.of(EXPIRY)));
        when(source.currentFnoOptionContracts(UNDERLYING, EXPIRY)).thenReturn(
            List.of(contract("NSE_FO|101", "CE"),
                contract("NSE_FO|102", "PE")));
        when(source.currentFnoOptionChain(UNDERLYING, EXPIRY)).thenReturn(
            new UpstoxAnalyticsSource.OptionChainSnapshot(
                "UPSTOX_ANALYTICS", MARKET, false,
                List.of(new UpstoxAnalyticsSource.FnoOptionStrike(
                    EXPIRY, UNDERLYING, new BigDecimal("25000"),
                    new BigDecimal("25025"),
                    quote("NSE_FO|101"), quote("NSE_FO|102")))));
    }

    private static UpstoxAnalyticsSource.FnoOptionContract contract(
            String key, String side) {
        return new UpstoxAnalyticsSource.FnoOptionContract(
            UNDERLYING, key, EXPIRY, new BigDecimal("25000"),
            side, 65, new BigDecimal("0.05"), "NIFTY " + side);
    }
    private static UpstoxAnalyticsSource.FnoOptionQuote quote(String key) {
        return new UpstoxAnalyticsSource.FnoOptionQuote(
            key, new BigDecimal("100"), new BigDecimal("99"),
            new BigDecimal("101"), 1, 1000, 900,
            null, null, null, null, null);
    }

    @Test
    void neverReadsBrokerOrStartsWithoutDisplayRights() {
        eligibleSource();
        manager(false).reconcile();
        verifyNoInteractions(catalog, service);
        verify(source, never()).currentFnoOptionContracts(anyString(), any());
        verify(source, never()).authorizeFnoMarketFeedV3();
    }

    @Test
    void startsOnlyVerifiedProviderPairedContractsDuringActualSession() {
        eligibleSource();
        var auto = manager(true);
        auto.reconcile();
        verify(service).start(List.of("NSE_FO|101", "NSE_FO|102"));
        assertThat(auto.status().autoOwned()).isTrue();
        assertThat(auto.status().expiry()).isEqualTo(EXPIRY);
        assertThat(auto.status().status()).isEqualTo("AUTO_CONNECTING");

        // Once connected, manager must neither redial nor hit provider REST.
        when(service.status()).thenReturn(status("CONNECTED"));
        auto.reconcile();
        verify(service, times(1)).start(anyList());
        verify(source, times(1)).currentFnoOptionContracts(anyString(), any());
    }

    @Test
    void doesNotTakeOverAnOperatorManagedSocket() {
        eligibleSource();
        when(service.status()).thenReturn(status("CONNECTED"));
        var auto = manager(true);
        auto.reconcile();
        assertThat(auto.status().status()).isEqualTo("MANUAL_CONTROL");
        verify(service, never()).stop();
        verify(service, never()).start(anyList());
        verifyNoInteractions(catalog);
    }

    @Test
    void closesOnlyItsOwnSocketAtSessionEnd() {
        eligibleSource();
        var auto = manager(true);
        auto.reconcile();
        when(calendar.isInsideFnoSession(any(Instant.class))).thenReturn(false);
        clock.value = MARKET.plusSeconds(60 * 60 * 8);
        auto.reconcile();
        assertThat(auto.status().status()).isEqualTo("MARKET_CLOSED");
        assertThat(auto.status().autoOwned()).isFalse();
        verify(service, times(1)).stop();
    }

    @Test
    void aSilentConnectedSocketIsStoppedWithThreeMinuteBackoff() {
        eligibleSource();
        var auto = manager(true);
        auto.reconcile();
        when(service.status()).thenReturn(status("CONNECTED"));
        clock.value = MARKET.plusSeconds(91);
        auto.reconcile();
        assertThat(auto.status().status()).isEqualTo("STALE_SOCKET_BACKOFF");
        assertThat(auto.status().autoOwned()).isFalse();
        verify(service).stop();
        // Real feed.stop() returns the socket to STOPPED; model that reset.
        when(service.status()).thenReturn(status("STOPPED"));
        clock.value = MARKET.plusSeconds(120);
        auto.reconcile();
        assertThat(auto.status().status()).isEqualTo("BACKOFF");
        verify(service, times(1)).start(anyList());
    }

    @Test
    void deniedListingBacksOffAndCannotLoopProviderRequests() {
        eligibleSource();
        when(catalog.listing(any(Instant.class))).thenReturn(
            new CustomerFnoExpiryCatalog.Listing(
                "BLOCKED_SOURCE_UNAVAILABLE", null, List.of()));
        var auto = manager(true);
        auto.reconcile();
        clock.value = MARKET.plusSeconds(30);
        auto.reconcile();
        assertThat(auto.status().status()).isEqualTo("BACKOFF");
        verify(catalog, times(1)).listing(any());
        verify(service, never()).start(anyList());
    }
}
