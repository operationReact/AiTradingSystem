package in.aitradingsystem.tradingrooms;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import in.aitradingsystem.marketdata.FnoVerifiedNiftyCreditSpreadSourceStore;
import in.aitradingsystem.marketdata.FnoVerifiedNiftyLongOptionSourceStore;
import in.aitradingsystem.marketdata.MarketSessionCalendar;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

class FnoCreditSpreadResearchServiceTest {
    private static final Instant NOW = Instant.parse("2026-11-20T05:00:00Z");
    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    private static final String ACCOUNT = "private_margin_account";
    private static final String PROVIDER = "UPSTOX_ANALYTICS";
    private static final String INDEX = "NSE_INDEX|Nifty 50";
    private static final String BULL_LOW = "NSE_FO|NIFTY_20261127_120_PE";
    private static final String BULL_HIGH = "NSE_FO|NIFTY_20261127_125_PE";
    private static final String BEAR_LOW = "NSE_FO|NIFTY_20261127_75_CE";
    private static final String BEAR_HIGH = "NSE_FO|NIFTY_20261127_80_CE";

    private static MarketSessionCalendar calendar() {
        var cal = mock(MarketSessionCalendar.class);
        when(cal.isInsideCashSession(NOW)).thenReturn(true);
        when(cal.isTradingDay(any(LocalDate.class))).thenAnswer(inv -> {
            LocalDate day = inv.getArgument(0);
            return day.getDayOfWeek().getValue() <= 5;
        });
        return cal;
    }

    private static List<FnoVerifiedNiftyLongOptionSourceStore.VerifiedClose>
            closes(String latest) {
        var sessions = new ArrayList<LocalDate>();
        LocalDate day = LocalDate.of(2026, 11, 19);
        while (sessions.size() < 35) {
            if (day.getDayOfWeek().getValue() <= 5) sessions.add(0, day);
            day = day.minusDays(1);
        }
        var rows = new ArrayList<FnoVerifiedNiftyLongOptionSourceStore.VerifiedClose>();
        for (int i = 0; i < sessions.size(); i++) {
            Instant closeAt = sessions.get(i).atTime(15,30).atZone(IST).toInstant();
            rows.add(new FnoVerifiedNiftyLongOptionSourceStore.VerifiedClose(
                PROVIDER, INDEX, sessions.get(i),
                new BigDecimal(i == 34 ? latest : "100"),
                closeAt.plusSeconds(60), closeAt));
        }
        return rows;
    }

    private static FnoVerifiedNiftyCreditSpreadSourceStore.Leg leg(
            String key, String kind, String strike, String bid, String ask) {
        return new FnoVerifiedNiftyCreditSpreadSourceStore.Leg(
            key, kind, LocalDate.of(2026,11,1), LocalDate.of(2026,11,27),
            50, new BigDecimal(strike), new BigDecimal("0.05"),
            new BigDecimal(bid), new BigDecimal(ask), 10, 10, 1000,
            NOW.minusSeconds(40), NOW.minusSeconds(30));
    }

    private static FnoVerifiedNiftyCreditSpreadSourceStore.Proof proof(boolean bullish) {
        var lower = bullish
            ? leg(BULL_LOW, "PE", "120", "1.95", "2")
            : leg(BEAR_LOW, "CE", "75", "5", "5.10");
        var upper = bullish
            ? leg(BULL_HIGH, "PE", "125", "5", "5.10")
            : leg(BEAR_HIGH, "CE", "80", "1.95", "2");
        var tick = new FnoVerifiedNiftyCreditSpreadSourceStore.IndexTick(
            new BigDecimal(bullish ? "125" : "75"),
            NOW.minusSeconds(40), NOW.minusSeconds(30));
        var costs = new FnoVerifiedNiftyCreditSpreadSourceStore.Risk(
            50, 50, new BigDecimal("10"), 300, 2, 50, 5000,
            new BigDecimal("10000"), 3000, 3000);
        var capital = new FnoVerifiedNiftyCreditSpreadSourceStore.Capital(
            new BigDecimal("200000"), new BigDecimal("20000"),
            new BigDecimal("10000"), new BigDecimal("5000"),
            new BigDecimal("1000"));
        return new FnoVerifiedNiftyCreditSpreadSourceStore.Proof(
            PROVIDER, ACCOUNT, bullish ? "BULL_PUT" : "BEAR_CALL",
            INDEX, lower, upper, tick, costs, capital,
            "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa");
    }

    private static FnoVerifiedNiftyLongOptionSourceStore daily(String latest) {
        var store = mock(FnoVerifiedNiftyLongOptionSourceStore.class);
        when(store.verifiedRecentCloses(NOW)).thenReturn(closes(latest));
        return store;
    }

    private static FnoVerifiedNiftyCreditSpreadSourceStore spread() {
        var store = mock(FnoVerifiedNiftyCreditSpreadSourceStore.class);
        when(store.oneCurrentSpread(ACCOUNT, "BULL_PUT", BULL_LOW, BULL_HIGH, NOW))
            .thenReturn(Optional.of(proof(true)));
        when(store.oneCurrentSpread(ACCOUNT, "BEAR_CALL", BEAR_LOW, BEAR_HIGH, NOW))
            .thenReturn(Optional.of(proof(false)));
        return store;
    }

    private static FnoCreditSpreadResearchService service(
            FnoVerifiedNiftyLongOptionSourceStore daily,
            FnoVerifiedNiftyCreditSpreadSourceStore spread,
            boolean rights, String account) {
        return new FnoCreditSpreadResearchService(
            daily, spread, calendar(), rights, rights, rights, rights, account,
            BULL_LOW, BULL_HIGH, BEAR_LOW, BEAR_HIGH);
    }

    private static Map<String,FnoFuturesResearchService.StrategyStatus> report(
            FnoCreditSpreadResearchService service) {
        return service.evaluateAll(NOW).stream().collect(
            Collectors.toMap(FnoFuturesResearchService.StrategyStatus::strategyId, s->s));
    }

    @Test
    void historicalCreditSourceReadsOnlySelectedTwoLegPairAndAccountMargin(){
        var daily=daily("125");var source=spread();
        var r=service(daily,source,true,ACCOUNT).evaluateOne("strategy.STR-185",NOW);
        assertEquals("RESEARCH_CANDIDATE",r.status());
        assertEquals("VERIFIED_TWO_LEG_PE_CREDIT_SPREAD_RESEARCH_NO_ORDER",r.inputState());
        verify(daily).verifiedRecentCloses(NOW);
        verify(source).oneCurrentSpread(ACCOUNT,"BULL_PUT",BULL_LOW,BULL_HIGH,NOW);
        verify(source,never()).oneCurrentSpread(eq(ACCOUNT),eq("BEAR_CALL"),any(),any(),any());
        verifyNoMoreInteractions(source,daily);
    }

    @Test
    void absentAccountOrHistoricalRightsCannotQueryMarginSource(){
        var daily=daily("125");var source=spread();
        var missing=service(daily,source,true,"").evaluateOne("strategy.STR-186",NOW);
        assertEquals("CREDIT_SPREAD_ACCOUNT_OR_MARGIN_NOT_CONFIGURED",missing.reasonCode());
        verifyNoInteractions(daily,source);
        var denied=service(daily,source,false,ACCOUNT).evaluateOne("strategy.STR-186",NOW);
        assertEquals("CREDIT_SPREAD_SOURCE_RIGHTS_UNVERIFIED",denied.reasonCode());
        verifyNoInteractions(daily,source);
    }

    @Test
    void bullishThirtyFiveSourceClosesRunTrueBullPutCreditEvaluatorWithoutExecution() {
        var daily = daily("125");
        var source = spread();
        var rows = report(service(daily,source,true,ACCOUNT));
        var bull = rows.get("strategy.STR-185");
        assertEquals("RESEARCH_CANDIDATE", bull.status());
        assertEquals("BULL_PUT_CREDIT_SPREAD_RESEARCH_SETUP_ONLY", bull.reasonCode());
        assertEquals(2, bull.contractsResolved());
        assertEquals(3, bull.freshQuotes());
        assertEquals(35, bull.historySessions());
        assertEquals("VERIFIED_TWO_LEG_PE_CREDIT_SPREAD_RESEARCH_NO_ORDER",
            bull.inputState());
        assertEquals("NO_SIGNAL", rows.get("strategy.STR-186").status());
        verify(daily, times(1)).verifiedRecentCloses(NOW);
        verify(source).oneCurrentSpread(ACCOUNT,"BULL_PUT",BULL_LOW,BULL_HIGH,NOW);
        verify(source).oneCurrentSpread(ACCOUNT,"BEAR_CALL",BEAR_LOW,BEAR_HIGH,NOW);
    }

    @Test
    void bearishThirtyFiveClosesRunTrueBearCallCreditEvaluatorWithHigherStrikeHedge() {
        var rows = report(service(daily("75"), spread(), true, ACCOUNT));
        assertEquals("NO_SIGNAL", rows.get("strategy.STR-185").status());
        assertEquals("RESEARCH_CANDIDATE", rows.get("strategy.STR-186").status());
        assertEquals("BEAR_CALL_CREDIT_SPREAD_RESEARCH_SETUP_ONLY",
            rows.get("strategy.STR-186").reasonCode());
        assertEquals("VERIFIED_TWO_LEG_CE_CREDIT_SPREAD_RESEARCH_NO_ORDER",
            rows.get("strategy.STR-186").inputState());
    }

    @Test
    void withoutDistinctBrokerSourceRightsNeitherMarginNorMarketRowsAreRead() {
        var daily = mock(FnoVerifiedNiftyLongOptionSourceStore.class);
        var spread = mock(FnoVerifiedNiftyCreditSpreadSourceStore.class);
        var rows = report(service(daily,spread,false,ACCOUNT));
        assertEquals("BLOCKED_RIGHTS", rows.get("strategy.STR-185").status());
        assertEquals("BLOCKED_RIGHTS", rows.get("strategy.STR-186").status());
        assertEquals(0, rows.get("strategy.STR-185").contractsResolved());
        verifyNoInteractions(daily,spread);
    }

    @Test
    void missingAccountScopeOrMarginReceiptsCannotMakeCreditSpreadTradeReady() {
        var daily = mock(FnoVerifiedNiftyLongOptionSourceStore.class);
        var source = mock(FnoVerifiedNiftyCreditSpreadSourceStore.class);
        var noAccount = report(service(daily,source,true,""));
        assertEquals("CREDIT_SPREAD_ACCOUNT_OR_MARGIN_NOT_CONFIGURED",
            noAccount.get("strategy.STR-185").reasonCode());
        verifyNoInteractions(daily,source);
        var noEvidence = report(service(daily("125"),source,true,ACCOUNT));
        assertEquals("BLOCKED_DATA",noEvidence.get("strategy.STR-185").status());
        assertEquals("CREDIT_SPREAD_TWO_LEG_QUOTES_COSTS_OR_DEPTH_PROOF_MISSING",
            noEvidence.get("strategy.STR-185").reasonCode());
        assertEquals(0,noEvidence.get("strategy.STR-185").freshQuotes());
    }

    @Test
    void brokerCashBelowMarginVariationAndSettlementReserveBlocksEvenWithPositiveCredit() {
        var source = spread();
        var p = proof(true);
        var small = new FnoVerifiedNiftyCreditSpreadSourceStore.Capital(
            new BigDecimal("1000"), new BigDecimal("20000"),
            new BigDecimal("10000"), new BigDecimal("5000"),
            new BigDecimal("1000"));
        when(source.oneCurrentSpread(ACCOUNT,"BULL_PUT",BULL_LOW,BULL_HIGH,NOW))
            .thenReturn(Optional.of(new FnoVerifiedNiftyCreditSpreadSourceStore.Proof(
                p.provider(),p.accountScope(),p.spreadType(),p.underlyingKey(),
                p.lower(),p.higher(),p.index(),p.risk(),small,p.contractMasterDigest())));
        var row = report(service(daily("125"),source,true,ACCOUNT)).get("strategy.STR-185");
        assertEquals("BLOCKED_DATA",row.status());
        assertEquals("CREDIT_SPREAD_BROKER_MARGIN_RESERVES_INSUFFICIENT",row.reasonCode());
        assertEquals(0,row.contractsResolved());
    }

    @Test
    void theoreticalSpreadLossAboveSourceReviewedStressAllowanceFailsClosed() {
        var source = spread();
        var p = proof(true);
        var cap = new FnoVerifiedNiftyCreditSpreadSourceStore.Capital(
            new BigDecimal("200000"),new BigDecimal("20000"),
            new BigDecimal("10000"),new BigDecimal("5000"),new BigDecimal("1"));
        when(source.oneCurrentSpread(ACCOUNT,"BULL_PUT",BULL_LOW,BULL_HIGH,NOW))
            .thenReturn(Optional.of(new FnoVerifiedNiftyCreditSpreadSourceStore.Proof(
                p.provider(),p.accountScope(),p.spreadType(),p.underlyingKey(),
                p.lower(),p.higher(),p.index(),p.risk(),cap,p.contractMasterDigest())));
        var row = report(service(daily("125"),source,true,ACCOUNT)).get("strategy.STR-185");
        assertEquals("BLOCKED_DATA",row.status());
        assertEquals("CREDIT_SPREAD_STRESSED_LOSS_EXCEEDS_RECONCILED_CAP",row.reasonCode());
    }

    @Test
    void crossProviderOptionProofDoesNotReuseAnotherAccountsMarginAssumptions() {
        var source = spread();
        var p = proof(true);
        when(source.oneCurrentSpread(ACCOUNT,"BULL_PUT",BULL_LOW,BULL_HIGH,NOW))
            .thenReturn(Optional.of(new FnoVerifiedNiftyCreditSpreadSourceStore.Proof(
                "ANGEL_ONE_SMARTAPI",p.accountScope(),p.spreadType(),p.underlyingKey(),
                p.lower(),p.higher(),p.index(),p.risk(),p.capital(),p.contractMasterDigest())));
        var row = report(service(daily("125"),source,true,ACCOUNT)).get("strategy.STR-185");
        assertEquals("BLOCKED_DATA",row.status());
        assertEquals("CREDIT_SPREAD_PROVIDER_CONTRACT_OR_FIRST_KNOWN_MISMATCH",row.reasonCode());
    }

    @Test
    void privateBrokerSourceErrorsAreRedactedAndNeverPlaceOrders() {
        var source = spread();
        when(source.oneCurrentSpread(ACCOUNT,"BULL_PUT",BULL_LOW,BULL_HIGH,NOW))
            .thenThrow(new IllegalStateException("secret broker balance and access token"));
        var row = report(service(daily("125"),source,true,ACCOUNT)).get("strategy.STR-185");
        assertEquals("BLOCKED_DATA",row.status());
        assertEquals("CREDIT_SPREAD_SOURCE_READ_UNAVAILABLE",row.reasonCode());
        assertFalse(row.toString().contains("secret"));
    }
}
