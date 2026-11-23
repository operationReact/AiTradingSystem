package in.aitradingsystem.tradingrooms;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import in.aitradingsystem.provider.upstox.UpstoxFnoV3LiveFeedService;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.core.Authentication;

class CustomerMindFnoPaperCycleServiceTest {
    static final Instant NOW=Instant.parse("2026-11-20T05:00:00Z"); // 10:30 IST
    private final UUID owner=UUID.randomUUID(),room=UUID.randomUUID();
    private final Authentication auth=mock(Authentication.class);
    private final CustomerMindFnoResearchController research=mock(CustomerMindFnoResearchController.class);
    private final CustomerPaperFnoOrderService orders=mock(CustomerPaperFnoOrderService.class);
    private final CustomerPaperWalletService wallets=mock(CustomerPaperWalletService.class);
    private final CustomerPaperFnoCapabilityController capability=mock(CustomerPaperFnoCapabilityController.class);
    private final CustomerPaperFnoReconciliationService recon=mock(CustomerPaperFnoReconciliationService.class);
    private final CustomerPaperSharedWalletAuditService cashAudit=mock(CustomerPaperSharedWalletAuditService.class);
    private final UpstoxFnoV3LiveFeedService feed=mock(UpstoxFnoV3LiveFeedService.class);
    private final JdbcClient jdbc=mock(JdbcClient.class,RETURNS_DEEP_STUBS);
    private final CustomerMindFnoPaperCycleService cycle =
        new CustomerMindFnoPaperCycleService(research,orders,wallets,capability,
            recon,cashAudit,feed,jdbc,Clock.fixed(NOW,ZoneOffset.UTC));

    @BeforeEach
    void ready() {
        when(capability.capability(room,auth)).thenReturn(ResponseEntity.ok(
            new CustomerPaperFnoCapabilityController.Capability(
                "SOURCE_CHECK_REQUIRED",true,true,false,List.of())));
        when(wallets.get(owner,room)).thenReturn(
            wallet(10_000_000L,10_000_000L,false));
        when(cashAudit.inspect(owner,room)).thenReturn(new CustomerPaperSharedWalletAuditService.Audit(
            "CASH_AND_REALIZED_PNL_MATCHED","CASH_AND_REALIZED_PNL_ONLY",NOW,
            0,0,1,10_000_000L,10_000_000L,0L,0L,
            true,false,false,false,false,List.of()));
        when(recon.inspect(owner,room)).thenReturn(new CustomerPaperFnoReconciliationService.Receipt(
            "NO_FILLS",NOW,0,0,0,0,0,0L,0L,List.of(),false,false,false,false));
        when(orders.positions(owner,room)).thenReturn(List.of());
    }

    private CustomerPaperWalletService.PaperWallet wallet(long initial,long cash,boolean halted) {
        return new CustomerPaperWalletService.PaperWallet(room,"PAPER","PAPER_FUNDED",
            initial,cash,0L,0L,halted,1L,NOW,true);
    }

    private CustomerMindFnoResearchController.FnoResearch validWorkerCandidate(String strategy) {
        when(capability.internalCapability(owner,room)).thenReturn(
            new CustomerPaperFnoCapabilityController.Capability(
                "SOURCE_CHECK_REQUIRED",true,true,false,List.of()));
        var result=mock(CustomerMindFnoResearchController.FnoResearch.class);
        when(result.status()).thenReturn("RESEARCH_CANDIDATE");
        when(result.strategyId()).thenReturn(strategy);
        when(result.signal()).thenReturn("strategy.STR-181".equals(strategy)
            ? "BUY_CALL_RESEARCH" : "BUY_PUT_RESEARCH");
        when(result.quoteSource()).thenReturn("UPSTOX_V3_EXCHANGE_TIMED");
        when(result.exchangeAt()).thenReturn(NOW.minusSeconds(2));
        when(result.observedAt()).thenReturn(NOW.minusSeconds(1));
        when(result.latestCompletedCloseAt()).thenReturn(NOW.minusSeconds(86400));
        when(result.ask()).thenReturn(new BigDecimal("125"));
        when(result.lotSize()).thenReturn(65);
        when(result.instrumentKey()).thenReturn("NSE_FO|61001");
        when(result.expiry()).thenReturn(LocalDate.of(2026,11,26));
        when(research.inspectForOwnedPaperWorker()).thenReturn(result);
        return result;
    }

    @Test
    void futureAndStaleSignalBarsNeverReachPaperOrders() {
        for (Instant invalidClose : List.of(NOW.plusSeconds(60),
                NOW.minusSeconds(5*86400L))) {
            reset(research,capability);
            var candidate=validWorkerCandidate("strategy.STR-181");
            when(candidate.latestCompletedCloseAt()).thenReturn(invalidClose);
            var result=cycle.runOptedIn(owner,room);
            assertThat(result.reasonCode()).isEqualTo("FNO_SIGNAL_PROVENANCE_INVALID");
            assertThat(result.action()).isEqualTo("NONE");
            assertThat(result.liveMoney()).isFalse();
            verify(orders,never()).submit(any(),any(),any(),any(),any(),any(),anyInt(),any());
        }
    }

    @Test
    void nullResearchResponseFailsClosedWithoutAnyPaperOrder() {
        when(capability.internalCapability(owner,room)).thenReturn(
            new CustomerPaperFnoCapabilityController.Capability(
                "SOURCE_CHECK_REQUIRED",true,true,false,List.of()));
        var result=cycle.runOptedIn(owner,room);
        assertThat(result.status()).isEqualTo("BLOCKED");
        assertThat(result.reasonCode()).isEqualTo("FNO_RESEARCH_RESPONSE_MISSING");
        assertThat(result.liveMoney()).isFalse();
        verify(orders,never()).submit(any(),any(),any(),any(),any(),any(),anyInt(),any());
    }

    @Test
    void quoteOlderThanFifteenSecondsByOneMillisecondIsRejected() {
        var candidate=validWorkerCandidate("strategy.STR-181");
        when(candidate.exchangeAt()).thenReturn(NOW.minusSeconds(15).minusMillis(1));
        when(candidate.observedAt()).thenReturn(NOW.minusSeconds(15));
        var result=cycle.runOptedIn(owner,room);
        assertThat(result.reasonCode()).isEqualTo("FNO_RESEARCH_QUOTE_STALE");
        assertThat(result.action()).isEqualTo("NONE");
        verify(orders,never()).submit(any(),any(),any(),any(),any(),any(),anyInt(),any());
    }

    @Test
    void quoteObservationBeforeExchangeTimeIsRejectedEvenIfOtherwiseFresh() {
        var candidate=validWorkerCandidate("strategy.STR-181");
        when(candidate.exchangeAt()).thenReturn(NOW.minusSeconds(1));
        when(candidate.observedAt()).thenReturn(NOW.minusSeconds(2));
        var result=cycle.runOptedIn(owner,room);
        assertThat(result.reasonCode()).isEqualTo("FNO_RESEARCH_QUOTE_STALE");
        assertThat(result.action()).isEqualTo("NONE");
        verify(orders,never()).submit(any(),any(),any(),any(),any(),any(),anyInt(),any());
    }

    @Test
    void duplicateLongPutCrossoverNeverSubmitsAnotherPaperOrder() {
        validWorkerCandidate("strategy.STR-182");
        when(jdbc.sql(anyString()).param(eq("owner"),eq(owner))
            .param(eq("room"),eq(room)).param(eq("key"),anyString())
            .query(Boolean.class).single()).thenReturn(true);
        var result=cycle.runOptedIn(owner,room);
        assertThat(result.reasonCode()).isEqualTo("MIND_FNO_SIGNAL_ALREADY_TRADED");
        assertThat(result.action()).isEqualTo("NONE");
        verify(orders,never()).submit(any(),any(),any(),any(),any(),any(),anyInt(),any());
    }

    @Test
    void putSignalRoutesOnlyOneLongPutVirtualBuyAndRejectsSafely() {
        validWorkerCandidate("strategy.STR-182");
        when(jdbc.sql(anyString()).param(eq("owner"),eq(owner))
            .param(eq("room"),eq(room)).param(eq("key"),anyString())
            .query(Boolean.class).single()).thenReturn(false);
        when(orders.submit(eq(owner),eq(room),anyString(),
            eq("NSE_FO|61001"),eq(LocalDate.of(2026,11,26)),
            eq("BUY"),eq(1),eq(new BigDecimal("125"))))
            .thenThrow(new org.springframework.web.server.ResponseStatusException(
                org.springframework.http.HttpStatus.CONFLICT,"Paper risk rejected"));
        var result=cycle.runOptedIn(owner,room);
        assertThat(result.status()).isEqualTo("ORDER_REJECTED");
        assertThat(result.action()).isEqualTo("NONE");
        assertThat(result.liveMoney()).isFalse();
        verify(orders,times(1)).submit(eq(owner),eq(room),anyString(),
            eq("NSE_FO|61001"),eq(LocalDate.of(2026,11,26)),
            eq("BUY"),eq(1),eq(new BigDecimal("125")));
    }

    @Test
    void unknownVirtualSubmissionOutcomeNeverRetriesOrClaimsNoFill() {
        validWorkerCandidate("strategy.STR-181");
        when(jdbc.sql(anyString()).param(eq("owner"),eq(owner))
            .param(eq("room"),eq(room)).param(eq("key"),anyString())
            .query(Boolean.class).single()).thenReturn(false);
        when(orders.submit(eq(owner),eq(room),anyString(),anyString(),
            any(LocalDate.class),eq("BUY"),eq(1),any(BigDecimal.class)))
            .thenThrow(new IllegalStateException("Simulated ledger uncertainty"));
        var result=cycle.runOptedIn(owner,room);
        assertThat(result.status()).isEqualTo("UNKNOWN");
        assertThat(result.reasonCode()).isEqualTo("PAPER_ORDER_OUTCOME_UNKNOWN");
        assertThat(result.action()).isEqualTo("NONE");
        assertThat(result.fillId()).isNull();
        verify(orders,times(1)).submit(eq(owner),eq(room),anyString(),anyString(),
            any(LocalDate.class),eq("BUY"),eq(1),any(BigDecimal.class));
    }

    @Test
    void noPaperRightsMeansNoResearchOrVirtualOrder() {
        when(capability.capability(room,auth)).thenReturn(ResponseEntity.ok(
            new CustomerPaperFnoCapabilityController.Capability(
                "PAPER_USE_RIGHTS_NOT_VERIFIED",true,false,false,
                List.of("PAPER_USE_RIGHTS_NOT_VERIFIED"))));
        var r=cycle.run(owner,room,auth);
        assertThat(r.status()).isEqualTo("BLOCKED");
        assertThat(r.liveMoney()).isFalse();
        verifyNoInteractions(research,orders,jdbc,feed);
    }

    @Test
    void pausedWalletNeverEvaluatesSignalOrPlacesPaperOrder() {
        when(wallets.get(owner,room)).thenReturn(wallet(10_000_000L,10_000_000L,true));
        assertThat(cycle.run(owner,room,auth).reasonCode()).isEqualTo("PAPER_WALLET_PAUSED");
        verifyNoInteractions(research,orders,feed,jdbc);
    }

    @Test
    void badReconciliationPreventsEntryAndExit() {
        when(recon.inspect(owner,room)).thenReturn(new CustomerPaperFnoReconciliationService.Receipt(
            "INCONSISTENT",NOW,2,1,1,0,1,10L,0L,
            List.of("REALIZED_PNL_MISMATCH"),false,false,false,false));
        assertThat(cycle.run(owner,room,auth).reasonCode()).isEqualTo("PAPER_RECONCILIATION_MISMATCH");
        verifyNoInteractions(research,feed);
        verify(orders,never()).submit(any(),any(),any(),any(),any(),any(),anyInt(),any());
    }

    @Test
    void noCrossoverIsNoTradeNotSyntheticFill() {
        var d=mock(CustomerMindFnoResearchController.FnoResearch.class);
        when(d.status()).thenReturn("NO_TRADE");
        when(d.reasonCode()).thenReturn("NO_NEW_DIRECTIONAL_CROSSOVER");
        when(research.inspect(auth)).thenReturn(d);
        var r=cycle.run(owner,room,auth);
        assertThat(r.status()).isEqualTo("NO_TRADE");
        assertThat(r.reasonCode()).isEqualTo("NO_NEW_DIRECTIONAL_CROSSOVER");
        verify(orders,never()).submit(any(),any(),any(),any(),any(),any(),anyInt(),any());
    }

    @Test
    void oneLotPremiumDoesNotExceedTenPercentOfVirtualInitialCapital() {
        assertThat(CustomerMindFnoPaperCycleService.premiumWithinBudget(
            1_000_000,1_000_000,new BigDecimal("10"),65)).isTrue();
        assertThat(CustomerMindFnoPaperCycleService.premiumWithinBudget(
            1_000_000,1_000_000,new BigDecimal("20"),65)).isFalse();
        assertThat(CustomerMindFnoPaperCycleService.premiumWithinBudget(
            1_000_000,40_000,new BigDecimal("10"),65)).isFalse();
        assertThat(CustomerMindFnoPaperCycleService.premiumWithinBudget(
            Long.MAX_VALUE,Long.MAX_VALUE,new BigDecimal("10"),65)).isFalse();
    }

    @Test
    void exitPolicyHonorsVirtualLossProfitExpiryAndClose() {
        assertThat(CustomerMindFnoPaperCycleService.shouldExit(100_000,-7_999,false,false)).isFalse();
        assertThat(CustomerMindFnoPaperCycleService.shouldExit(100_000,-8_000,false,false)).isTrue();
        assertThat(CustomerMindFnoPaperCycleService.shouldExit(100_000,14_999,false,false)).isFalse();
        assertThat(CustomerMindFnoPaperCycleService.shouldExit(100_000,15_000,false,false)).isTrue();
        assertThat(CustomerMindFnoPaperCycleService.shouldExit(100_000,0,true,false)).isTrue();
        assertThat(CustomerMindFnoPaperCycleService.shouldExit(100_000,0,false,true)).isTrue();
    }

    @Test
    void corruptPaperPositionMetadataAndBasisNeverTriggerVirtualSell() {
        var expiry=LocalDate.of(2026,11,26);
        var invalid=List.of(
            new CustomerPaperFnoOrderService.Position(
                "NSE_FO|61001","CE",expiry,65,65,0L,25_000L,true),
            new CustomerPaperFnoOrderService.Position(
                "NSE_FO|61001","CE",expiry,65,65,-1L,25_000L,true),
            new CustomerPaperFnoOrderService.Position(
                null,"CE",expiry,65,65,100_000L,25_000L,true),
            new CustomerPaperFnoOrderService.Position(
                "NSE_FO|61001","INVALID",expiry,65,65,100_000L,25_000L,true),
            new CustomerPaperFnoOrderService.Position(
                "NSE_FO|61001",null,expiry,65,65,100_000L,25_000L,true),
            new CustomerPaperFnoOrderService.Position(
                "NSE_FO|61001","CE",null,65,65,100_000L,25_000L,true),
            new CustomerPaperFnoOrderService.Position(
                "NSE_FO|61001","CE",expiry,0,0,100_000L,25_000L,true),
            new CustomerPaperFnoOrderService.Position(
                "NSE_FO|61001","CE",expiry,10001,10001,100_000L,25_000L,true)
        );
        for (var position : invalid) {
            when(orders.positions(owner,room)).thenReturn(List.of(position));
            var result=cycle.run(owner,room,auth);
            assertThat(result.status()).isEqualTo("BLOCKED");
            assertThat(result.reasonCode()).isEqualTo("UNSUPPORTED_PAPER_POSITION");
            assertThat(result.action()).isEqualTo("NONE");
            assertThat(result.liveMoney()).isFalse();
        }
        when(orders.positions(owner,room)).thenReturn(java.util.Collections.singletonList(null));
        var absent=cycle.run(owner,room,auth);
        assertThat(absent.reasonCode()).isEqualTo("UNSUPPORTED_PAPER_POSITION");
        assertThat(absent.action()).isEqualTo("NONE");
        verify(orders,never()).submit(any(),any(),any(),any(),any(),any(),anyInt(),any());
        verifyNoInteractions(jdbc,feed,research);
        assertThat(CustomerMindFnoPaperCycleService.shouldExit(0L,500_000L,true,true))
            .isFalse();
    }

    @Test
    void manualPaperPositionIsHeldNotSoldByMind() {
        var position=new CustomerPaperFnoOrderService.Position(
            "NSE_FO|61001","CE",LocalDate.of(2026,11,26),
            65,65,900_000L,100_000L,true);
        when(orders.positions(owner,room)).thenReturn(List.of(position));
        when(jdbc.sql(anyString()).param(eq("owner"),eq(owner))
            .param(eq("room"),eq(room)).param(eq("key"),eq(position.instrumentKey()))
            .query(String.class).optional()).thenReturn(Optional.of("manual_123456789"));
        var r=cycle.run(owner,room,auth);
        assertThat(r.status()).isEqualTo("HOLD");
        assertThat(r.reasonCode()).isEqualTo("EXISTING_POSITION_NOT_MIND_MANAGED");
        verify(orders,never()).submit(any(),any(),any(),any(),any(),any(),anyInt(),any());
    }

    @Test
    void optedInWorkerNeverNeedsUserTokenAndAbstainsOnNoCrossover() {
        when(capability.internalCapability(owner,room)).thenReturn(
            new CustomerPaperFnoCapabilityController.Capability(
                "SOURCE_CHECK_REQUIRED",true,true,false,List.of()));
        var researchResult=mock(CustomerMindFnoResearchController.FnoResearch.class);
        when(researchResult.status()).thenReturn("NO_TRADE");
        when(researchResult.reasonCode()).thenReturn("NO_NEW_DIRECTIONAL_CROSSOVER");
        when(research.inspectForOwnedPaperWorker()).thenReturn(researchResult);
        var result=cycle.runOptedIn(owner,room);
        assertThat(result.status()).isEqualTo("NO_TRADE");
        assertThat(result.liveMoney()).isFalse();
        verify(research,never()).inspect(any());
        verify(orders,never()).submit(any(),any(),any(),any(),any(),any(),anyInt(),any());
    }

    @Test
    void verifiedWorkerCandidateDelegatesOneLotOnlyToVirtualOrderEngine() {
        when(capability.internalCapability(owner,room)).thenReturn(
            new CustomerPaperFnoCapabilityController.Capability(
                "SOURCE_CHECK_REQUIRED",true,true,false,List.of()));
        var researchResult=mock(CustomerMindFnoResearchController.FnoResearch.class);
        when(researchResult.status()).thenReturn("RESEARCH_CANDIDATE");
        when(researchResult.strategyId()).thenReturn("strategy.STR-181");
        when(researchResult.signal()).thenReturn("BUY_CALL_RESEARCH");
        when(researchResult.quoteSource()).thenReturn("UPSTOX_V3_EXCHANGE_TIMED");
        when(researchResult.exchangeAt()).thenReturn(NOW.minusSeconds(2));
        when(researchResult.observedAt()).thenReturn(NOW.minusSeconds(1));
        when(researchResult.latestCompletedCloseAt()).thenReturn(NOW.minusSeconds(86400));
        when(researchResult.ask()).thenReturn(new BigDecimal("125"));
        when(researchResult.lotSize()).thenReturn(65);
        when(researchResult.instrumentKey()).thenReturn("NSE_FO|61001");
        when(researchResult.expiry()).thenReturn(LocalDate.of(2026,11,26));
        when(research.inspectForOwnedPaperWorker()).thenReturn(researchResult);
        when(jdbc.sql(anyString()).param(eq("owner"),eq(owner))
            .param(eq("room"),eq(room)).param(eq("key"),anyString())
            .query(Boolean.class).single()).thenReturn(false);
        var fill=mock(CustomerPaperFnoOrderService.Fill.class);
        when(fill.id()).thenReturn(UUID.randomUUID());
        when(fill.instrumentKey()).thenReturn("NSE_FO|61001");
        when(fill.realizedPnlDeltaPaise()).thenReturn(0L);
        when(fill.cashAfterPaise()).thenReturn(9_000_000L);
        when(orders.submit(eq(owner),eq(room),anyString(),
            eq("NSE_FO|61001"),eq(LocalDate.of(2026,11,26)),
            eq("BUY"),eq(1),eq(new BigDecimal("125")))).thenReturn(fill);
        when(recon.inspect(owner,room)).thenReturn(
            new CustomerPaperFnoReconciliationService.Receipt(
                "BOOKKEEPING_MATCHED",NOW,1,1,0,0,1,0L,0L,
                List.of(),false,false,false,false));
        var result=cycle.runOptedIn(owner,room);
        assertThat(result.status()).isEqualTo("FILLED_RECONCILED");
        assertThat(result.action()).isEqualTo("ENTER");
        assertThat(result.fillId()).isEqualTo(fill.id());
        assertThat(result.liveMoney()).isFalse();
        verify(orders,times(1)).submit(eq(owner),eq(room),anyString(),
            eq("NSE_FO|61001"),eq(LocalDate.of(2026,11,26)),
            eq("BUY"),eq(1),eq(new BigDecimal("125")));
    }


    @Test
    void mindOwnedOneLotAtProfitTargetClosesWithVirtualSellAndAuditedPnl() {
        var position=new CustomerPaperFnoOrderService.Position(
            "NSE_FO|61001","CE",LocalDate.of(2026,11,26),
            65,65,100_000L,15_000L,true);
        when(orders.positions(owner,room)).thenReturn(List.of(position));
        when(jdbc.sql(anyString()).param(eq("owner"),eq(owner))
            .param(eq("room"),eq(room)).param(eq("key"),eq(position.instrumentKey()))
            .query(String.class).optional()).thenReturn(
                Optional.of("mindfno_entry_12345678901234567890123456789012"));
        when(feed.eligiblePaperQuote(position.instrumentKey())).thenReturn(
            Optional.of(new UpstoxFnoV3LiveFeedService.PaperQuote(
                position.instrumentKey(),new BigDecimal("20.00"),
                new BigDecimal("21.00"),NOW.minusSeconds(2),NOW.minusSeconds(1))));
        var fill=mock(CustomerPaperFnoOrderService.Fill.class);
        when(fill.id()).thenReturn(UUID.randomUUID());
        when(fill.instrumentKey()).thenReturn(position.instrumentKey());
        when(fill.realizedPnlDeltaPaise()).thenReturn(15_000L);
        when(fill.cashAfterPaise()).thenReturn(11_000_000L);
        when(orders.submit(eq(owner),eq(room),anyString(),
            eq(position.instrumentKey()),eq(position.expiry()),
            eq("SELL"),eq(1),eq(new BigDecimal("20.00")))).thenReturn(fill);
        when(recon.inspect(owner,room)).thenReturn(
            new CustomerPaperFnoReconciliationService.Receipt(
                "BOOKKEEPING_MATCHED",NOW,2,1,1,1,0,15_000L,15_000L,
                List.of(),false,false,false,false));
        var result=cycle.run(owner,room,auth);
        assertThat(result.status()).isEqualTo("FILLED_RECONCILED");
        assertThat(result.action()).isEqualTo("EXIT");
        assertThat(result.realizedPnlDeltaPaise()).isEqualTo(15_000L);
        assertThat(result.liveMoney()).isFalse();
        verify(orders,times(1)).submit(eq(owner),eq(room),anyString(),
            eq(position.instrumentKey()),eq(position.expiry()),
            eq("SELL"),eq(1),eq(new BigDecimal("20.00")));
    }

}
