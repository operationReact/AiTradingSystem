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
    void requiresIndependentPaperAndV3Flags() {
        var flag=CustomerPaperFnoMarketController.class.getAnnotation(
            ConditionalOnProperty.class);
        assertThat(flag.name()).containsExactlyInAnyOrder(
            "minttick.upstox-fno.paper-enabled","minttick.upstox-fno.v3-feed-enabled");
        assertThat(flag.havingValue()).isEqualTo("true");
    }
}
