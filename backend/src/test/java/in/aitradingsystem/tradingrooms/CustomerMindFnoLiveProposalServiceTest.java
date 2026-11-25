package in.aitradingsystem.tradingrooms;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import in.aitradingsystem.provider.upstox.CustomerUpstoxOAuthService;
import in.aitradingsystem.provider.upstox.UpstoxAnalyticsSource;
import in.aitradingsystem.provider.upstox.UpstoxFnoLimitOrderPayload;
import in.aitradingsystem.provider.upstox.UpstoxFnoV3LiveFeedService;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.core.Authentication;

class CustomerMindFnoLiveProposalServiceTest {
    private static final String NIFTY="NSE_INDEX|Nifty 50";
    private static final Instant NOW=Instant.parse("2026-11-20T05:00:00Z");
    private static final LocalDate EXPIRY=LocalDate.parse("2026-11-26");
    private static final String KEY="NSE_FO|61001";
    private final UUID owner=UUID.randomUUID(),room=UUID.randomUUID();
    private final Authentication auth=mock(Authentication.class);
    private final CustomerMindFnoResearchController research=mock(CustomerMindFnoResearchController.class);
    private final CustomerUpstoxFnoLiveOrderService live=mock(CustomerUpstoxFnoLiveOrderService.class);
    private final CustomerUpstoxOAuthService broker=mock(CustomerUpstoxOAuthService.class,RETURNS_DEEP_STUBS);
    private final UpstoxAnalyticsSource source=mock(UpstoxAnalyticsSource.class);
    private final UpstoxFnoV3LiveFeedService feed=mock(UpstoxFnoV3LiveFeedService.class);
    @SuppressWarnings("unchecked")
    private final ObjectProvider<UpstoxFnoV3LiveFeedService> feeds=mock(ObjectProvider.class);
    private final JdbcClient jdbc=mock(JdbcClient.class,RETURNS_DEEP_STUBS);
    private CustomerMindFnoLiveProposalService service;

    @BeforeEach
    void setup() {
        service=spy(new CustomerMindFnoLiveProposalService(
            research,live,broker,source,feeds,
            new UpstoxFnoLimitOrderPayload(new ObjectMapper()),jdbc,
            Clock.fixed(NOW,ZoneOffset.UTC)));
        room("INTRADAY","BALANCED",300_000L);
        when(live.capability(owner,room)).thenReturn(new CustomerUpstoxFnoLiveOrderService.Capability(
            true,true,true,true,true,true,25,true,List.of()));
    }

    private void room(String focus,String risk,long budget) {
        doReturn(new CustomerMindFnoLiveProposalService.Room(
            focus,risk,budget,"FNO"))
            .when(service).requireOwnerLiveRoom(owner,room);
    }

    private void researchCandidate() {
        var d=mock(CustomerMindFnoResearchController.FnoResearch.class);
        when(d.status()).thenReturn("RESEARCH_CANDIDATE");
        when(d.strategyId()).thenReturn("strategy.STR-181");
        when(d.signal()).thenReturn("BUY_CALL_RESEARCH");
        when(d.underlyingKey()).thenReturn(NIFTY);
        when(d.candleSource()).thenReturn("UPSTOX_ANALYTICS");
        when(d.completedCandles()).thenReturn(40);
        when(d.latestCompletedCloseAt()).thenReturn(NOW.minusSeconds(86400));
        when(d.quoteSource()).thenReturn("UPSTOX_V3_EXCHANGE_TIMED");
        when(d.instrumentKey()).thenReturn(KEY);
        when(d.expiry()).thenReturn(EXPIRY);
        when(d.strike()).thenReturn(new BigDecimal("25300"));
        when(d.lotSize()).thenReturn(65);
        when(d.exchangeAt()).thenReturn(NOW.minusSeconds(2));
        when(d.observedAt()).thenReturn(NOW.minusSeconds(1));
        when(research.inspect(auth)).thenReturn(d);
    }

    private void validLiveSources() throws Exception {
        when(source.currentFnoOptionContracts(NIFTY,EXPIRY)).thenReturn(List.of(
            new UpstoxAnalyticsSource.FnoOptionContract(NIFTY,KEY,EXPIRY,
                new BigDecimal("25300"),"CE",65,new BigDecimal("0.05"),"NIFTY CE")));
        when(feeds.getIfAvailable()).thenReturn(feed);
        when(feed.eligiblePaperQuote(KEY)).thenReturn(java.util.Optional.of(
            new UpstoxFnoV3LiveFeedService.PaperQuote(KEY,
                new BigDecimal("125"),new BigDecimal("126"),
                NOW.minusSeconds(2),NOW.minusSeconds(1))));
        var mapper=new ObjectMapper();
        when(broker.funds(owner).data()).thenReturn(mapper.readTree(
            "{\"equity\":{\"available_margin\":50000}}"));
        when(broker.orders(owner).data()).thenReturn(mapper.readTree("[]"));
        when(broker.positions(owner).data()).thenReturn(mapper.readTree("[]"));
        when(jdbc.sql(contains("COUNT(*) FROM customer_upstox_fno_live_order"))
            .param("owner",owner).query(Long.class).single()).thenReturn(0L);
        when(jdbc.sql("SELECT new_orders_halted FROM trading_control WHERE id=1")
            .query(Boolean.class).optional()).thenReturn(java.util.Optional.of(false));
    }

    @Test
    void genuineSignalWithAccountMarginProducesBoundedReadOnlyOneLotProposal() throws Exception {
        researchCandidate();
        validLiveSources();
        var result=service.inspect(owner,room,auth);
        assertThat(result.state()).isEqualTo("RISK_PASSED_DRY_RUN");
        assertThat(result.reasons()).isEmpty();
        assertThat(result.strategyId()).isEqualTo("strategy.STR-181");
        assertThat(result.signal()).isEqualTo("BUY_CALL_RESEARCH");
        assertThat(result.instrumentKey()).isEqualTo(KEY);
        assertThat(result.lots()).isEqualTo(1);
        assertThat(result.units()).isEqualTo(65);
        assertThat(result.limitPrice()).isEqualByComparingTo("126");
        assertThat(result.premiumInr()).isEqualByComparingTo("8190");
        assertThat(result.maximumPremiumInr()).isEqualByComparingTo("25000");
        assertThat(result.stopReference()).isLessThan(result.limitPrice());
        assertThat(result.targetReference()).isGreaterThan(result.limitPrice());
        assertThat(result.proposalId()).isNotBlank();
        assertThat(result.executionAuthorized()).isFalse();
        assertThat(result.orderPlaced()).isFalse();
        verify(live,never()).submit(any(),any(),anyString(),any(),anyBoolean());
        verify(broker,never()).submitVerifiedFnoOnce(any(),any());
    }

    @Test
    void brokerExecutionPolicyNotEnabledBlocksBeforeMarketResearch() {
        when(live.capability(owner,room)).thenReturn(
            new CustomerUpstoxFnoLiveOrderService.Capability(
                false,true,true,true,true,true,8,false,
                List.of("LIVE_ORDER_POLICY_NOT_ACTIVATED")));
        var result=service.inspect(owner,room,auth);
        assertThat(result.state()).isEqualTo("BLOCKED");
        assertThat(result.reasons()).contains("LIVE_ORDER_POLICY_NOT_ACTIVATED");
        assertThat(result.orderPlaced()).isFalse();
        verifyNoInteractions(research,source,feed);
    }

    @Test
    void absentResearchCrossoverDoesNotReachBrokerMarketOrOrderTransport() {
        var d=mock(CustomerMindFnoResearchController.FnoResearch.class);
        when(d.status()).thenReturn("NO_TRADE");
        when(d.reasonCode()).thenReturn("NO_NEW_DIRECTIONAL_CROSSOVER");
        when(research.inspect(auth)).thenReturn(d);
        var result=service.inspect(owner,room,auth);
        assertThat(result.state()).isEqualTo("BLOCKED");
        assertThat(result.reasons()).contains("MIND_FNO_NO_NEW_DIRECTIONAL_CROSSOVER");
        verifyNoInteractions(source,feed,broker);
        verify(live,never()).submit(any(),any(),anyString(),any(),anyBoolean());
    }

    @Test
    void staleExchangeQuoteBlocksEvenWithValidResearchAndRights() throws Exception {
        researchCandidate();
        validLiveSources();
        when(feed.eligiblePaperQuote(KEY)).thenReturn(java.util.Optional.of(
            new UpstoxFnoV3LiveFeedService.PaperQuote(KEY,
                new BigDecimal("125"),new BigDecimal("126"),
                NOW.minusSeconds(80),NOW.minusSeconds(79))));
        var result=service.inspect(owner,room,auth);
        assertThat(result.state()).isEqualTo("BLOCKED");
        assertThat(result.reasons()).contains("NFO_EXCHANGE_QUOTE_PROVENANCE_INVALID");
        verify(live,never()).submit(any(),any(),anyString(),any(),anyBoolean());
    }

    @Test
    void tooSmallRoomBudgetFailsOneLotEvenIfBrokerMarginIsSufficient() throws Exception {
        room("INTRADAY","CAUTIOUS",2_000L);
        researchCandidate();
        validLiveSources();
        var result=service.inspect(owner,room,auth);
        assertThat(result.state()).isEqualTo("BLOCKED");
        assertThat(result.reasons()).contains("ONE_LOT_EXCEEDS_LIVE_RISK_BUDGET");
        verify(live,never()).submit(any(),any(),anyString(),any(),anyBoolean());
    }

    @Test
    void existingBrokerPositionPreventsAdditionalOptionExposure() throws Exception {
        researchCandidate();
        validLiveSources();
        when(broker.positions(owner).data()).thenReturn(new ObjectMapper().readTree(
            "[{\"exchange\":\"NFO\",\"quantity\":65}]"));
        var result=service.inspect(owner,room,auth);
        assertThat(result.reasons()).contains("BROKER_ORDER_OR_POSITION_REQUIRES_RECONCILIATION");
    }

    @Test
    void riskPolicyAccountsForPremiumRatherThanAssumingStopsAreGuaranteed() {
        assertThat(CustomerMindFnoLiveProposalService.maxPremium(
            100_000L,"CAUTIOUS",new BigDecimal("100000")))
            .isEqualByComparingTo("5000");
        assertThat(CustomerMindFnoLiveProposalService.maxPremium(
            100_000L,"BALANCED",new BigDecimal("100000")))
            .isEqualByComparingTo("10000");
        assertThat(CustomerMindFnoLiveProposalService.maxPremium(
            100_000L,"AGGRESSIVE",new BigDecimal("100000")))
            .isEqualByComparingTo("15000");
        assertThat(CustomerMindFnoLiveProposalService.maxPremium(
            1_000_000L,"AGGRESSIVE",new BigDecimal("10000")))
            .isEqualByComparingTo("8000");
        assertThat(CustomerMindFnoLiveProposalService.maxPremium(
            100_000L,"UNKNOWN",new BigDecimal("100000"))).isNull();
    }

    @Test
    void simulatedBrokerAcksAndPartialsNeverAuthorizeAutomaticResubmission() {
        var complete=CustomerMindFnoLiveProposalService.simulateBrokerOutcome(
            "complete",65,65);
        assertThat(complete.state()).isEqualTo("COMPLETE");
        assertThat(complete.needsBrokerReconciliation()).isFalse();
        var partial=CustomerMindFnoLiveProposalService.simulateBrokerOutcome(
            "open",15,65);
        assertThat(partial.state()).isEqualTo("PARTIAL_NEEDS_RECONCILIATION");
        assertThat(partial.needsBrokerReconciliation()).isTrue();
        var unknown=CustomerMindFnoLiveProposalService.simulateBrokerOutcome(
            "timed_out",0,65);
        assertThat(unknown.state()).isEqualTo("UNKNOWN");
        assertThat(unknown.needsBrokerReconciliation()).isTrue();
        var reject=CustomerMindFnoLiveProposalService.simulateBrokerOutcome(
            "rejected",0,65);
        assertThat(reject.state()).isEqualTo("REJECTED");
        assertThat(complete.mayRetryAutomatically()).isFalse();
        assertThat(partial.mayRetryAutomatically()).isFalse();
        assertThat(unknown.mayRetryAutomatically()).isFalse();
        assertThat(reject.mayRetryAutomatically()).isFalse();
        verify(live,never()).submit(any(),any(),anyString(),any(),anyBoolean());
        verify(broker,never()).submitVerifiedFnoOnce(any(),any());
    }

    @Test
    void brokerBookAndUnknownPositionsFailClosed() throws Exception {
        var mapper=new ObjectMapper();
        assertThat(CustomerMindFnoLiveProposalService.cleanBrokerBook(
            mapper.readTree("[{\"status\":\"complete\"},{\"status\":\"rejected\"}]"))).isTrue();
        assertThat(CustomerMindFnoLiveProposalService.cleanBrokerBook(
            mapper.readTree("[{\"status\":\"open\"}]"))).isFalse();
        assertThat(CustomerMindFnoLiveProposalService.hasNfoExposure(
            mapper.readTree("[{\"exchange\":\"NFO\",\"quantity\":0}]"))).isFalse();
        assertThat(CustomerMindFnoLiveProposalService.hasNfoExposure(
            mapper.readTree("[{\"exchange\":\"NFO\"}]"))).isTrue();
    }
}
