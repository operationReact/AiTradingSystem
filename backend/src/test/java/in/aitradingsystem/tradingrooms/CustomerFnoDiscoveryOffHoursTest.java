package in.aitradingsystem.tradingrooms;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
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
import org.springframework.web.server.ResponseStatusException;

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
        verify(source, never()).listedFnoLastTrades(anyList());
    }

    @Test
    void openMarketStillRequiresLiveFeed() {
        when(source.enabled()).thenReturn(true);
        when(calendar.isInsideFnoSession(any(Instant.class))).thenReturn(true);
        var expiry = LocalDate.now(ZoneId.of("Asia/Kolkata")).plusDays(1);
        assertThatThrownBy(() -> controller(true).internalMarket(expiry))
            .isInstanceOf(ResponseStatusException.class)
            .hasMessageContaining("F&O exchange feed unavailable");
    }
}
