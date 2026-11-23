package in.aitradingsystem.tradingrooms;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.*;

import in.aitradingsystem.marketdata.MarketSessionCalendar;
import in.aitradingsystem.provider.upstox.UpstoxAnalyticsSource;
import in.aitradingsystem.provider.upstox.UpstoxFnoV3LiveFeedService;
import in.aitradingsystem.user.CustomerIdentityStore;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.mock.env.MockEnvironment;

class CustomerFnoDiscoveryOffHoursTest {
    private static final String NIFTY = "NSE_INDEX|Nifty 50";
    private static final String CE = "NSE_FO|61001";
    private static final String PE = "NSE_FO|61002";

    private final UpstoxAnalyticsSource source = mock(UpstoxAnalyticsSource.class);
    private final MarketSessionCalendar calendar = mock(MarketSessionCalendar.class);
    @SuppressWarnings("unchecked")
    private final ObjectProvider<UpstoxFnoV3LiveFeedService> feed =
        mock(ObjectProvider.class);

    private static UpstoxAnalyticsSource.FnoOptionQuote quote(String key) {
        return new UpstoxAnalyticsSource.FnoOptionQuote(
            key, new BigDecimal("999"), new BigDecimal("998"),
            new BigDecimal("1000"), 10, 20, 30,
            new BigDecimal(".21"), new BigDecimal(".5"),
            new BigDecimal(".001"), new BigDecimal("-2"),
            new BigDecimal("2"));
    }

    private LocalDate mockListedMetadata() {
        var expiry = LocalDate.now(ZoneId.of("Asia/Kolkata")).plusDays(1);
        when(source.enabled()).thenReturn(true);
        when(source.currentFnoOptionContracts(NIFTY, expiry)).thenReturn(List.of(
            new UpstoxAnalyticsSource.FnoOptionContract(NIFTY, CE, expiry,
                new BigDecimal("25000"), "CE", 65, new BigDecimal(".05"), "NIFTY 25000 CE"),
            new UpstoxAnalyticsSource.FnoOptionContract(NIFTY, PE, expiry,
                new BigDecimal("25000"), "PE", 65, new BigDecimal(".05"), "NIFTY 25000 PE")));
        when(source.currentFnoOptionChain(NIFTY, expiry)).thenReturn(
            new UpstoxAnalyticsSource.OptionChainSnapshot("UPSTOX_ANALYTICS",
                Instant.now(), false, List.of(
                    new UpstoxAnalyticsSource.FnoOptionStrike(expiry, NIFTY,
                        new BigDecimal("25000"), new BigDecimal("25010"),
                        quote(CE), quote(PE)))));
        return expiry;
    }

    private CustomerFnoDiscoveryController controller(boolean restAllowed) {
        var env = new MockEnvironment()
            .withProperty("minttick.upstox-fno.display-rights-verified", "true");
        if (restAllowed) env.withProperty(
            "minttick.upstox-fno.rest-last-price-display-rights-verified", "true");
        return new CustomerFnoDiscoveryController(
            mock(CustomerIdentityStore.class), source, feed,
            mock(CustomerFnoExpiryCatalog.class), calendar, env);
    }

    private static CustomerPaperFnoMarketController.Leg unpricedLeg(
            String key, String side) {
        return new CustomerPaperFnoMarketController.Leg(
            "NSE_FO|" + key, "NIFTY " + side, side, 65, new BigDecimal(".05"),
            null, null, null, null, false, false,
            null, null, null, null, null, null, null, null);
    }

    private static CustomerPaperFnoMarketController.Strike strike(
            String price, String callKey, String putKey) {
        return new CustomerPaperFnoMarketController.Strike(new BigDecimal(price),
            unpricedLeg(callKey, "CE"), unpricedLeg(putKey, "PE"));
    }

    @Test
    void lastTradeLookupPrioritizesNearestSpotInsteadOfLowestStrike() {
        var rows = List.of(
            strike("25100", "61001", "61002"),
            strike("25700", "61003", "61004"),
            strike("25900", "61005", "61006"));
        assertThat(CustomerFnoDiscoveryController.prioritizeLastTradeKeys(
            rows, new BigDecimal("25700"))).containsExactly(
                "NSE_FO|61003", "NSE_FO|61004",
                "NSE_FO|61005", "NSE_FO|61006",
                "NSE_FO|61001", "NSE_FO|61002");
        assertThat(rows.getFirst().strikePrice()).isEqualByComparingTo("25100");
    }

    @Test
    void closedMarketUsesAuthorizedRestLastTradeWithoutLiveSocket() {
        var expiry = mockListedMetadata();
        var tradeAt = Instant.now().minusSeconds(3600);
        when(source.listedFnoLastTrades(anyList())).thenReturn(Map.of(
            CE, new UpstoxAnalyticsSource.ListedLastTrade(
                CE, new BigDecimal("110.25"), new BigDecimal("100.00"),
                2500L, tradeAt, tradeAt.plusSeconds(2))));
        var result = controller(true).internalMarket(expiry);
        assertThat(result.status()).isEqualTo("MARKET_CLOSED");
        assertThat(result.liveOrderEnabled()).isFalse();
        assertThat(result.strikes()).hasSize(1);
        var call = result.strikes().getFirst().call();
        assertThat(call.ltp()).isEqualByComparingTo("110.25");
        assertThat(call.openInterest()).isEqualTo(2500L);
        assertThat(call.quoteFresh()).isFalse();
        assertThat(call.paperEligible()).isFalse();
        assertThat(call.bid()).isNull();
        assertThat(call.ask()).isNull();
        // Snapshot values never overwrite V3 trade fields.
        assertThat(result.chainSnapshotIndicators().get(CE).ltp())
            .isEqualByComparingTo("999");
        verify(feed).getIfAvailable();
        verifyNoMoreInteractions(feed);
    }

    @Test
    void closedMarketDoesNotUseRestWithoutIndependentRights() {
        var expiry = mockListedMetadata();
        var result = controller(false).internalMarket(expiry);
        assertThat(result.status()).isEqualTo("MARKET_CLOSED");
        assertThat(result.strikes()).hasSize(1);
        assertThat(result.strikes().getFirst().call().ltp()).isNull();
        // Real V2 chain metrics are returned in a distinct, non-executable map.
        var reference = result.chainSnapshotIndicators().get(CE);
        assertThat(reference.ltp()).isEqualByComparingTo("999");
        assertThat(reference.openInterest()).isEqualTo(20L);
        assertThat(reference.volume()).isEqualTo(10L);
        assertThat(reference.previousOpenInterest()).isEqualTo(30L);
        assertThat(reference.iv()).isEqualByComparingTo(".21");
        assertThat(reference.bid()).isEqualByComparingTo("998");
        assertThat(result.strikes().getFirst().call().quoteFresh()).isFalse();
        assertThat(result.strikes().getFirst().call().paperEligible()).isFalse();
        verify(source, never()).listedFnoLastTrades(anyList());
    }

    @Test
    void retainedSnapshotDatabaseFailureStillAllowsSeparatelyAuthorizedRestLastTrade() {
        var expiry = mockListedMetadata();
        var tradeAt = Instant.now().minusSeconds(3600);
        when(source.listedFnoLastTrades(anyList())).thenReturn(Map.of(
            CE, new UpstoxAnalyticsSource.ListedLastTrade(
                CE, new BigDecimal("110.25"), new BigDecimal("100"),
                2500L, tradeAt, tradeAt.plusSeconds(2))));
        var brokenStore = mock(CustomerFnoLastTradeSnapshotStore.class);
        when(brokenStore.latestAll(anyList()))
            .thenThrow(new IllegalStateException("read-only snapshot unavailable"));
        var subject = controller(true);
        subject.setLastTrades(brokenStore);

        var result = subject.internalMarket(expiry);
        var call = result.strikes().getFirst().call();
        assertThat(call.ltp()).isEqualByComparingTo("110.25");
        assertThat(call.openInterest()).isEqualTo(2500L);
        assertThat(call.quoteFresh()).isFalse();
        assertThat(call.paperEligible()).isFalse();
        assertThat(call.bid()).isNull();
        assertThat(call.ask()).isNull();
        verify(brokenStore).latestAll(anyList());
        verify(source).listedFnoLastTrades(anyList());
    }

    @Test
    void brokenRetainedSnapshotCannotBypassIndependentRestDisplayRights() {
        var expiry = mockListedMetadata();
        var brokenStore = mock(CustomerFnoLastTradeSnapshotStore.class);
        when(brokenStore.latestAll(anyList()))
            .thenThrow(new IllegalStateException("read-only snapshot unavailable"));
        var subject = controller(false);
        subject.setLastTrades(brokenStore);

        var result = subject.internalMarket(expiry);
        assertThat(result.strikes().getFirst().call().ltp()).isNull();
        assertThat(result.strikes().getFirst().call().quoteFresh()).isFalse();
        assertThat(result.strikes().getFirst().call().paperEligible()).isFalse();
        verify(source, never()).listedFnoLastTrades(anyList());
    }

    @Test
    void openMarketWithoutV3FeedStillShowsAuthorizedReadOnlyListedChain() {
        var expiry = mockListedMetadata();
        when(calendar.isInsideFnoSession(any(Instant.class))).thenReturn(true);
        var result = controller(true).internalMarket(expiry);
        assertThat(result.status()).isEqualTo("NO_FRESH_QUOTES");
        assertThat(result.strikes()).hasSize(1);
        assertThat(result.strikes().getFirst().call().ltp()).isNull();
        assertThat(result.strikes().getFirst().call().quoteFresh()).isFalse();
        assertThat(result.strikes().getFirst().call().paperEligible()).isFalse();
        assertThat(result.strikes().getFirst().call().bid()).isNull();
        assertThat(result.chainSnapshotIndicators().get(CE).ltp())
            .isEqualByComparingTo("999");
        verify(feed).getIfAvailable();
        verifyNoMoreInteractions(feed);
        // Never claim an off-hours REST last trade is a fresh open-market tick.
        verify(source, never()).listedFnoLastTrades(anyList());
    }
}
