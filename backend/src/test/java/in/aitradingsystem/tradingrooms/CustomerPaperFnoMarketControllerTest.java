package in.aitradingsystem.tradingrooms;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

import in.aitradingsystem.marketdata.MarketSessionCalendar;
import in.aitradingsystem.provider.upstox.UpstoxAnalyticsSource;
import in.aitradingsystem.provider.upstox.UpstoxFnoV3LiveFeedService;
import in.aitradingsystem.user.CustomerIdentityStore;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.web.server.ResponseStatusException;

class CustomerPaperFnoMarketControllerTest {
    private static final String NIFTY="NSE_INDEX|Nifty 50";
    private static final LocalDate EXPIRY=LocalDate.parse("2026-11-17");
    private static final String CE="NSE_FO|61001";
    private static final String PE="NSE_FO|61002";
    private final UpstoxFnoV3LiveFeedService live=mock(UpstoxFnoV3LiveFeedService.class);
    private final UpstoxAnalyticsSource source=mock(UpstoxAnalyticsSource.class);
    private final JdbcClient jdbc=mock(JdbcClient.class);
    private final CustomerIdentityStore identity=mock(CustomerIdentityStore.class);
    private final MarketSessionCalendar calendar=mock(MarketSessionCalendar.class);

    private static UpstoxAnalyticsSource.FnoOptionContract contract(String key,String side,
                                                                       String strike) {
        return new UpstoxAnalyticsSource.FnoOptionContract(
            NIFTY,key,EXPIRY,new BigDecimal(strike),side,65,new BigDecimal(".05"),
            "NIFTY "+side);
    }
    private static UpstoxAnalyticsSource.FnoOptionQuote option(String key) {
        // REST quote values are intentionally extreme to ensure they never leak.
        return new UpstoxAnalyticsSource.FnoOptionQuote(key,new BigDecimal("999"),
            new BigDecimal("998"),new BigDecimal("1000"),10,20,30,
            new BigDecimal("0.21"),new BigDecimal(".5"),new BigDecimal(".001"),
            new BigDecimal("-2"),new BigDecimal("2"));
    }
    private static UpstoxAnalyticsSource.OptionChainSnapshot chain() {
        return new UpstoxAnalyticsSource.OptionChainSnapshot("UPSTOX_ANALYTICS",
            Instant.parse("2026-11-14T08:00:00Z"),false,List.of(
            new UpstoxAnalyticsSource.FnoOptionStrike(EXPIRY,NIFTY,
                new BigDecimal("25000"),new BigDecimal("25012"),
                option(CE),option(PE))));
    }

    @Test
    void customerDiscoveryDisplaysAllProviderListedPairsBeyondTradeTerminalLimit() {
        var contracts = new java.util.ArrayList<UpstoxAnalyticsSource.FnoOptionContract>();
        var strikes = new java.util.ArrayList<UpstoxAnalyticsSource.FnoOptionStrike>();
        for (int i = 0; i < 30; i++) {
            String price = Integer.toString(25000 + 50 * i);
            String call = "NSE_FO|" + (61000 + 2 * i);
            String put = "NSE_FO|" + (61001 + 2 * i);
            contracts.add(contract(call, "CE", price));
            contracts.add(contract(put, "PE", price));
            strikes.add(new UpstoxAnalyticsSource.FnoOptionStrike(EXPIRY, NIFTY,
                new BigDecimal(price), new BigDecimal("25012"),
                option(call), option(put)));
        }
        var chain = new UpstoxAnalyticsSource.OptionChainSnapshot(
            "UPSTOX_ANALYTICS", Instant.parse("2026-11-14T08:00:00Z"),
            false, List.copyOf(strikes));
        var selected = CustomerPaperFnoMarketController.select(
            contracts, chain, live, false, true);
        var discovery = CustomerPaperFnoMarketController.selectDiscovery(
            contracts, chain, live, false);
        assertThat(selected).hasSize(24);
        assertThat(discovery).hasSize(30);
        assertThat(discovery).allSatisfy(row -> {
            assertThat(row.call().ltp()).isNull();
            assertThat(row.put().bid()).isNull();
            assertThat(row.call().paperEligible()).isFalse();
            assertThat(row.put().paperEligible()).isFalse();
        });
        verifyNoInteractions(live);
    }

    @Test
    void usesOnlyLiveV3EvidenceForCustomerPricesAndNoRestChainBidAsk() {
        var ts=Instant.parse("2026-11-16T04:01:00Z");
        when(live.eligiblePaperQuote(CE)).thenReturn(Optional.of(
            new UpstoxFnoV3LiveFeedService.PaperQuote(
                CE,new BigDecimal("110"),new BigDecimal("112"),ts,ts.plusSeconds(1))));
        var rows=CustomerPaperFnoMarketController.select(
            List.of(contract(CE,"CE","25000"),contract(PE,"PE","25000")),
            chain(),live,true,true);
        assertThat(rows).hasSize(1);
        assertThat(rows.getFirst().call().bid()).isEqualByComparingTo("110");
        assertThat(rows.getFirst().call().ask()).isEqualByComparingTo("112");
        assertThat(rows.getFirst().call().paperEligible()).isTrue();
        assertThat(rows.getFirst().call().quoteFresh()).isTrue();
        assertThat(rows.getFirst().put().bid()).isNull();
        assertThat(rows.getFirst().put().ask()).isNull();
        assertThat(rows.getFirst().put().paperEligible()).isFalse();
        verify(live).eligiblePaperQuote(CE);
        verify(live).eligiblePaperQuote(PE);
    }

    @Test
    void authorizedFreshV3ExposesLtpPriorCloseOiGreeksButNeverRestValues() {
        var ts=Instant.parse("2026-11-16T04:01:00Z");
        when(live.eligiblePaperQuote(CE)).thenReturn(Optional.of(
            new UpstoxFnoV3LiveFeedService.PaperQuote(
                CE,new BigDecimal("110"),new BigDecimal("112"),ts,ts.plusSeconds(1))));
        when(live.eligibleDisplayQuote(CE)).thenReturn(Optional.of(
            new UpstoxFnoV3LiveFeedService.DisplayQuote(
                CE,new BigDecimal("111"),new BigDecimal("105"),4001L,
                new BigDecimal(".18"),new BigDecimal(".4"),new BigDecimal("-3.5"),
                new BigDecimal(".002"),new BigDecimal("5.1"),
                new BigDecimal("110"),new BigDecimal("112"),ts,ts.plusSeconds(1),
                23000L, 300L, 550L, 65L)));
        var rows=CustomerPaperFnoMarketController.select(
            List.of(contract(CE,"CE","25000"),contract(PE,"PE","25000")),
            chain(),live,true,false);
        var call=rows.getFirst().call();
        assertThat(call.ltp()).isEqualByComparingTo("111");
        assertThat(call.previousClose()).isEqualByComparingTo("105");
        assertThat(call.openInterest()).isEqualTo(4001L);
        assertThat(call.volume()).isEqualTo(23000L);
        assertThat(call.bidQuantity()).isEqualTo(300L);
        assertThat(call.askQuantity()).isEqualTo(550L);
        assertThat(call.lastTradedQuantity()).isEqualTo(65L);
        assertThat(call.delta()).isEqualByComparingTo(".4");
        assertThat(call.theta()).isEqualByComparingTo("-3.5");
        assertThat(call.iv()).isEqualByComparingTo(".18");
        assertThat(call.paperEligible()).isFalse();
        assertThat(rows.getFirst().put().ltp()).isNull();
        assertThat(rows.getFirst().put().openInterest()).isNull();
    }

    @Test
    void mismatchedPriceObservationNeverMixesPricesAndGreeks() {
        var ts=Instant.parse("2026-11-16T04:01:00Z");
        when(live.eligiblePaperQuote(CE)).thenReturn(Optional.of(
            new UpstoxFnoV3LiveFeedService.PaperQuote(
                CE,new BigDecimal("110"),new BigDecimal("112"),ts,ts)));
        when(live.eligibleDisplayQuote(CE)).thenReturn(Optional.of(
            new UpstoxFnoV3LiveFeedService.DisplayQuote(
                CE,new BigDecimal("111"),new BigDecimal("105"),4001L,
                new BigDecimal(".18"),new BigDecimal(".4"),new BigDecimal("-3.5"),
                new BigDecimal(".002"),new BigDecimal("5.1"),
                new BigDecimal("109"),new BigDecimal("112"),ts,ts)));
        var rows=CustomerPaperFnoMarketController.select(
            List.of(contract(CE,"CE","25000"),contract(PE,"PE","25000")),
            chain(),live,true,false);
        assertThat(rows.getFirst().call().bid()).isEqualByComparingTo("110");
        assertThat(rows.getFirst().call().ltp()).isNull();
        assertThat(rows.getFirst().call().openInterest()).isNull();
        assertThat(rows.getFirst().call().delta()).isNull();
    }

    @Test
    void discoveryDisplaysFreshV3LtpWithoutExecutableBidAsk() {
        var ts = Instant.parse("2026-11-16T04:01:00Z");
        when(live.eligibleDisplayQuote(CE)).thenReturn(Optional.of(
            new UpstoxFnoV3LiveFeedService.DisplayQuote(
                CE, new BigDecimal("111"), new BigDecimal("105"), 4001L,
                null, null, null, null, null,
                null, null, ts, ts.plusSeconds(1),
                18000L, 300L, 200L, 20L)));
        var rows = CustomerPaperFnoMarketController.selectDiscovery(
            List.of(contract(CE,"CE","25000"),contract(PE,"PE","25000")),
            chain(), live, true);
        assertThat(rows).hasSize(1);
        assertThat(rows.getFirst().call().ltp()).isEqualByComparingTo("111");
        assertThat(rows.getFirst().call().openInterest()).isEqualTo(4001L);
        assertThat(rows.getFirst().call().volume()).isEqualTo(18000L);
        assertThat(rows.getFirst().call().lastTradedQuantity()).isEqualTo(20L);
        // Display-only contract intentionally has no executable depth.
        assertThat(rows.getFirst().call().bidQuantity()).isNull();
        assertThat(rows.getFirst().call().askQuantity()).isNull();
        assertThat(rows.getFirst().call().quoteFresh()).isTrue();
        assertThat(rows.getFirst().call().paperEligible()).isFalse();
        assertThat(rows.getFirst().call().bid()).isNull();
        verify(live, never()).eligiblePaperQuote(CE);
    }

    @Test
    void closedMarketNeverLeaksProviderSnapshotPricesAsExecutable() {
        var rows=CustomerPaperFnoMarketController.select(
            List.of(contract(CE,"CE","25000"),contract(PE,"PE","25000")),
            chain(),live,false,true);
        assertThat(rows).hasSize(1);
        assertThat(rows.getFirst().call().bid()).isNull();
        assertThat(rows.getFirst().put().ask()).isNull();
        assertThat(rows.getFirst().call().paperEligible()).isFalse();
        verifyNoInteractions(live);
    }

    @Test
    void unverifiedPaperUsageRightsDisableButtonsEvenWithFreshQuote() {
        var ts=Instant.parse("2026-11-16T04:01:00Z");
        when(live.eligiblePaperQuote(CE)).thenReturn(Optional.of(
            new UpstoxFnoV3LiveFeedService.PaperQuote(
                CE,new BigDecimal("110"),new BigDecimal("112"),ts,ts)));
        var rows=CustomerPaperFnoMarketController.select(
            List.of(contract(CE,"CE","25000"),contract(PE,"PE","25000")),
            chain(),live,true,false);
        assertThat(rows.getFirst().call().quoteFresh()).isTrue();
        assertThat(rows.getFirst().call().paperEligible()).isFalse();
    }

    @Test
    void mismatchedOrUnlistedCePeCannotBeOfferedToTrade() {
        assertThat(CustomerPaperFnoMarketController.select(
            List.of(contract(CE,"CE","25000"),
                    contract(PE,"PE","26000")),chain(),live,true,true)).isEmpty();
        assertThat(CustomerPaperFnoMarketController.select(
            List.of(contract(CE,"CE","25000")),chain(),live,true,true)).isEmpty();
        verifyNoInteractions(live);
    }

    @Test
    void rejectsUnknownCustomerBeforeAnySourceApiRead() {
        var controller=new CustomerPaperFnoMarketController(jdbc,identity,source,
            live,calendar,Clock.fixed(Instant.parse("2026-11-16T04:00:00Z"),
                ZoneOffset.UTC),false,false);
        assertThatThrownBy(()->controller.market(UUID.randomUUID(),EXPIRY,null))
            .isInstanceOf(ResponseStatusException.class)
            .hasMessageContaining("Customer required");
        verifyNoInteractions(source,live);
    }

    @Test
    void providerContractSourceFailureIsControlled503WithoutLeakingDetails() {
        var error=org.assertj.core.api.Assertions.catchThrowable(
            () -> CustomerPaperFnoMarketController.providerRead(
                () -> { throw new IllegalStateException(
                    "UPSTOX_FNO_TRANSPORT_FAILURE secret-bearer-should-not-leak"); }));
        assertThat(error).isInstanceOf(ResponseStatusException.class);
        var response=(ResponseStatusException)error;
        assertThat(response.getStatusCode().value()).isEqualTo(503);
        assertThat(response.getReason()).isEqualTo(
            "F&O provider contracts or chain unavailable");
        assertThat(response.getMessage()).doesNotContain("secret-bearer-should-not-leak");
        assertThat(response.getCause()).isNull();
    }

    @Test
    void malformedProviderContractFailsClosedWhileValidResultPasses() {
        assertThatThrownBy(() -> CustomerPaperFnoMarketController.providerRead(
            () -> { throw new IllegalArgumentException("raw upstream JSON"); }))
            .isInstanceOf(ResponseStatusException.class)
            .hasMessageContaining("503 SERVICE_UNAVAILABLE")
            .hasMessageNotContaining("raw upstream JSON");
        assertThat(CustomerPaperFnoMarketController.providerRead(
            () -> List.of("safe-contract-key"))).containsExactly("safe-contract-key");
    }

    @Test
    void marketResponseReflectsVerifiedPaperUsePermissionWithoutGrantingLive() {
        var time = Instant.parse("2026-11-17T04:01:00Z");
        var rows = CustomerPaperFnoMarketController.select(
            List.of(contract(CE, "CE", "25000"), contract(PE, "PE", "25000")),
            chain(), live, false, true);
        var permitted = CustomerPaperFnoMarketController.marketResponse(
            EXPIRY, time, "MARKET_CLOSED", true, true, rows);
        assertThat(permitted.displayRightsVerified()).isTrue();
        assertThat(permitted.trainingRightsVerified()).isTrue();
        assertThat(permitted.liveOrderEnabled()).isFalse();
        assertThat(permitted.strikes().getFirst().call().paperEligible()).isFalse();

        var denied = CustomerPaperFnoMarketController.marketResponse(
            EXPIRY, time, "MARKET_CLOSED", true, false, rows);
        assertThat(denied.trainingRightsVerified()).isFalse();
        assertThat(denied.liveOrderEnabled()).isFalse();
    }

    @Test
    void realNseOptionsReadOnlyRequiresV3WithoutRequiringPaperOrders() {
        var flag=CustomerPaperFnoMarketController.class.getAnnotation(
            ConditionalOnProperty.class);
        assertThat(flag.name()).containsExactly(
            "minttick.upstox-fno.v3-feed-enabled");
        assertThat(flag.havingValue()).isEqualTo("true");
        // Permission to display quotes is checked per authenticated request;
        // it does not authorize PAPER fills or actual LIVE broker orders.
    }
}
