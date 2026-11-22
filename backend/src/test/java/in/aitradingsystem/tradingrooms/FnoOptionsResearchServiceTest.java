package in.aitradingsystem.tradingrooms;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import in.aitradingsystem.marketdata.FnoVerifiedNiftyLongOptionSourceStore;
import in.aitradingsystem.marketdata.MarketSessionCalendar;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

class FnoOptionsResearchServiceTest {
    private static final Instant NOW = Instant.parse("2026-11-20T05:00:00Z");
    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    private static final String INDEX = "NSE_INDEX|Nifty 50";
    private static final String CE = "NSE_FO|NIFTY_20261127_125_CE";
    private static final String PE = "NSE_FO|NIFTY_20261127_75_PE";
    private static final String PROVIDER = "UPSTOX_ANALYTICS";

    private static MarketSessionCalendar calendar() {
        MarketSessionCalendar cal = mock(MarketSessionCalendar.class);
        when(cal.isInsideCashSession(NOW)).thenReturn(true);
        when(cal.isTradingDay(any(LocalDate.class))).thenAnswer(inv -> {
            LocalDate d = inv.getArgument(0);
            return d.getDayOfWeek().getValue() <= 5;
        });
        return cal;
    }

    private static List<FnoVerifiedNiftyLongOptionSourceStore.VerifiedClose>
            bars(String latestClose) {
        var days = new ArrayList<LocalDate>();
        LocalDate date = LocalDate.of(2026, 11, 19);
        while (days.size() < 35) {
            if (date.getDayOfWeek().getValue() <= 5) days.add(0, date);
            date = date.minusDays(1);
        }
        var out = new ArrayList<FnoVerifiedNiftyLongOptionSourceStore.VerifiedClose>();
        for (int i = 0; i < days.size(); i++) {
            Instant closed = days.get(i).atTime(15,30).atZone(IST).toInstant();
            out.add(new FnoVerifiedNiftyLongOptionSourceStore.VerifiedClose(
                PROVIDER, INDEX, days.get(i),
                new BigDecimal(i == 34 ? latestClose : "100"),
                closed.plusSeconds(120), closed));
        }
        return out;
    }

    private static FnoVerifiedNiftyLongOptionSourceStore.OptionProof proof(boolean call) {
        return new FnoVerifiedNiftyLongOptionSourceStore.OptionProof(
            PROVIDER, INDEX, call ? CE : PE, call ? "CE" : "PE",
            LocalDate.of(2026, 11, 1), LocalDate.of(2026, 11, 27),
            50, new BigDecimal(call ? "125" : "75"), new BigDecimal("0.05"),
            new BigDecimal(call ? "125" : "75"),
            NOW.minusSeconds(40), NOW.minusSeconds(30),
            new BigDecimal("1.95"), new BigDecimal("2.00"), 10, 1000,
            NOW.minusSeconds(40), NOW.minusSeconds(30),
            call ? 500 : 1000, 50, new BigDecimal("10"),
            10000, 300, 1000, 2, 50, new BigDecimal("10000"), 3000,
            "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa");
    }

    private static FnoVerifiedNiftyLongOptionSourceStore store(String lastClose) {
        var store = mock(FnoVerifiedNiftyLongOptionSourceStore.class);
        when(store.verifiedRecentCloses(NOW)).thenReturn(bars(lastClose));
        when(store.oneCurrentOption(CE, "CE", NOW)).thenReturn(Optional.of(proof(true)));
        when(store.oneCurrentOption(PE, "PE", NOW)).thenReturn(Optional.of(proof(false)));
        return store;
    }

    private static FnoOptionsResearchService service(
            FnoVerifiedNiftyLongOptionSourceStore store, boolean rights,
            String callKey, String putKey) {
        return new FnoOptionsResearchService(store, calendar(), rights,
            rights, rights, callKey, putKey);
    }

    private static java.util.Map<String,FnoFuturesResearchService.StrategyStatus>
            report(FnoOptionsResearchService svc) {
        return svc.evaluateAll(NOW).stream().collect(Collectors.toMap(
            FnoFuturesResearchService.StrategyStatus::strategyId, x->x));
    }

    @Test
    void bullishThirtyFiveVerifiedDailyClosesInvokeLongCallAndDistinctLongPut() {
        var source = store("125");
        var rows = report(service(source, true, CE, PE));
        assertEquals(2, rows.size());
        var call = rows.get("strategy.STR-181");
        assertEquals("RESEARCH_CANDIDATE", call.status());
        assertEquals("LONG_CALL_BULLISH_SCENARIO_RESEARCH_ONLY", call.reasonCode());
        assertEquals(35, call.historySessions());
        assertEquals(2, call.freshQuotes());
        assertEquals("VERIFIED_NIFTY_CE_SOURCE_RESEARCH_ONLY", call.inputState());
        assertEquals("NO_SIGNAL", rows.get("strategy.STR-182").status());
        verify(source, times(1)).verifiedRecentCloses(NOW);
        verify(source).oneCurrentOption(CE, "CE", NOW);
        verify(source).oneCurrentOption(PE, "PE", NOW);
        verifyNoMoreInteractions(source);
    }

    @Test
    void bearishCrossoverResearchCandidateRunsPutNotBullishCall() {
        var rows = report(service(store("75"), true, CE, PE));
        assertEquals("NO_SIGNAL", rows.get("strategy.STR-181").status());
        assertEquals("RESEARCH_CANDIDATE", rows.get("strategy.STR-182").status());
        assertEquals("LONG_PUT_BEARISH_SCENARIO_RESEARCH_ONLY",
            rows.get("strategy.STR-182").reasonCode());
        assertEquals("VERIFIED_NIFTY_PE_SOURCE_RESEARCH_ONLY",
            rows.get("strategy.STR-182").inputState());
    }

    @Test
    void historicalOriginalCeEvaluatorInvokedWithoutUnnecessaryPutSourceQuery() {
        var source=store("125");
        var report=service(source,true,CE,PE).evaluateOne(
            "strategy.STR-181",NOW);
        assertEquals("RESEARCH_CANDIDATE",report.status());
        assertEquals("LONG_CALL_BULLISH_SCENARIO_RESEARCH_ONLY",report.reasonCode());
        assertEquals(35,report.historySessions());
        verify(source).verifiedRecentCloses(NOW);
        verify(source).oneCurrentOption(CE,"CE",NOW);
        verify(source,never()).oneCurrentOption(eq(PE),any(),any());
        verifyNoMoreInteractions(source);
    }

    @Test
    void historicalOriginalPeEvaluatorInvokedWithoutUnnecessaryCallSourceQuery() {
        var source=store("75");
        var report=service(source,true,CE,PE).evaluateOne(
            "strategy.STR-182",NOW);
        assertEquals("RESEARCH_CANDIDATE",report.status());
        assertEquals("LONG_PUT_BEARISH_SCENARIO_RESEARCH_ONLY",report.reasonCode());
        assertEquals(35,report.historySessions());
        verify(source).verifiedRecentCloses(NOW);
        verify(source).oneCurrentOption(PE,"PE",NOW);
        verify(source,never()).oneCurrentOption(eq(CE),any(),any());
        verifyNoMoreInteractions(source);
    }

    @Test
    void originalHistoricalOneSideHasNoFallbackWhenSourceRightsOrKeyMissing(){
        var source=store("125");
        var denied=service(source,false,CE,PE).evaluateOne(
            "strategy.STR-181",NOW);
        assertEquals("LONG_OPTION_SOURCE_RIGHTS_UNVERIFIED",denied.reasonCode());
        verifyNoInteractions(source);
        var source2=store("125");
        var missing=service(source2,true,"",PE).evaluateOne(
            "strategy.STR-181",NOW);
        assertEquals("LONG_OPTION_LISTED_CONTRACT_NOT_CONFIGURED",missing.reasonCode());
        verifyNoInteractions(source2);
        assertThrows(IllegalArgumentException.class,()->
            service(source2,true,CE,PE).evaluateOne("strategy.STR-191",NOW));
    }

    @Test
    void deniedRightsInvokeBothDistinctEvaluatorsWithoutAccessingDatabase() {
        var source = mock(FnoVerifiedNiftyLongOptionSourceStore.class);
        var rows = report(service(source, false, CE, PE));
        assertEquals("BLOCKED_RIGHTS", rows.get("strategy.STR-181").status());
        assertEquals("BLOCKED_RIGHTS", rows.get("strategy.STR-182").status());
        assertEquals("LONG_CALL_SOURCE_RIGHTS_UNVERIFIED",
            rows.get("strategy.STR-181").reasonCode());
        verifyNoInteractions(source);
    }

    @Test
    void missingGenuineContractKeyBlocksThatSideAndDoesNotInventAContract() {
        var source = store("125");
        var rows = report(service(source, true, "", PE));
        assertEquals("BLOCKED_DATA", rows.get("strategy.STR-181").status());
        assertEquals("LONG_OPTION_LISTED_CONTRACT_NOT_CONFIGURED",
            rows.get("strategy.STR-181").reasonCode());
        assertEquals("NO_SIGNAL", rows.get("strategy.STR-182").status());
        verify(source, never()).oneCurrentOption(eq(CE), any(), any());
    }

    @Test
    void fewerThanThirtyFiveClosesOrMissingTradingDayRejectBothOptions() {
        var source = store("125");
        when(source.verifiedRecentCloses(NOW)).thenReturn(bars("125").subList(0, 34));
        var result = report(service(source, true, CE, PE));
        assertEquals("BLOCKED_DATA", result.get("strategy.STR-181").status());
        assertEquals("LONG_OPTION_35_COMPLETED_SOURCE_CLOSES_MISSING",
            result.get("strategy.STR-182").reasonCode());
        verify(source, never()).oneCurrentOption(any(),any(),any());
        source = store("125");
        var withGap = new ArrayList<>(bars("125"));
        withGap.remove(25);
        when(source.verifiedRecentCloses(NOW)).thenReturn(withGap);
        var gap = report(service(source, true, CE, PE));
        assertEquals("LONG_OPTION_35_COMPLETED_SOURCE_CLOSES_MISSING",
            gap.get("strategy.STR-181").reasonCode());
    }

    @Test
    void mismatchBetweenProviderAndSourceListedOptionIsNotAnExecutionSignal() {
        var source = store("125");
        var p = proof(true);
        var wrong = new FnoVerifiedNiftyLongOptionSourceStore.OptionProof(
            "ANGEL_ONE_SMARTAPI", p.indexKey(), p.optionKey(), p.optionType(),
            p.listedFrom(), p.expiry(), p.lotSize(), p.strike(), p.tickSize(),
            p.indexPrice(), p.indexExchangeAt(), p.indexFirstObservedAt(),
            p.optionBid(), p.optionAsk(), p.askDepthLots(), p.openInterestLots(),
            p.optionExchangeAt(), p.optionFirstObservedAt(),
            p.hypotheticalMoveAtExpiryBps(), p.buySlippageBps(),
            p.allInFeesPerLotInr(), p.minScenarioRewardRiskBps(),
            p.maxOptionSpreadBps(), p.maxStrikeDistanceBps(),
            p.minAskDepthLots(), p.minOpenInterestLots(), p.researchCapitalInr(),
            p.maxCapitalAllocationBps(), p.contractMasterSha256());
        when(source.oneCurrentOption(CE, "CE", NOW)).thenReturn(Optional.of(wrong));
        var rows = report(service(source, true, CE, PE));
        assertEquals("BLOCKED_DATA", rows.get("strategy.STR-181").status());
        assertEquals("LONG_OPTION_SOURCE_CONTRACT_PROVIDER_OR_TIMING_MISMATCH",
            rows.get("strategy.STR-181").reasonCode());
        assertEquals("NO_SIGNAL", rows.get("strategy.STR-182").status());
    }

    @Test
    void missingDepthFeesOrOptionQuotePreventsResearchButDoesNotBlockOtherSide() {
        var source = store("125");
        when(source.oneCurrentOption(CE, "CE", NOW)).thenReturn(Optional.empty());
        var rows = report(service(source, true, CE, PE));
        assertEquals("LONG_OPTION_LISTED_QUOTE_DEPTH_OI_COST_PROOF_MISSING",
            rows.get("strategy.STR-181").reasonCode());
        assertEquals("NO_SIGNAL", rows.get("strategy.STR-182").status());
    }

    @Test
    void providerExceptionsAreRedactedAndRightsAreNeverPromoted() {
        var source = store("125");
        when(source.verifiedRecentCloses(NOW)).thenThrow(
            new IllegalStateException("sensitive option token"));
        var rows = report(service(source, true, CE, PE));
        assertEquals("BLOCKED_DATA", rows.get("strategy.STR-181").status());
        assertEquals("BLOCKED_DATA", rows.get("strategy.STR-182").status());
        assertEquals("LONG_OPTION_DAILY_SOURCE_READ_UNAVAILABLE",
            rows.get("strategy.STR-181").reasonCode());
        assertFalse(rows.get("strategy.STR-181").toString().contains("sensitive"));
    }
}
