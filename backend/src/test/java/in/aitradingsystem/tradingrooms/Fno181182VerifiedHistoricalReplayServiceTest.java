package in.aitradingsystem.tradingrooms;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import in.aitradingsystem.marketdata.FnoVerifiedNiftyLongOptionSourceStore;
import in.aitradingsystem.marketdata.MarketSessionCalendar;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * Synthetic SOURCE mock tests; no market-data licence, original actual
 * storage/first-observed receipts or historical market E2E can be inferred.
 */
class Fno181182VerifiedHistoricalReplayServiceTest {
    private static final Instant AS_OF = Instant.parse("2026-11-20T05:00:00Z");
    private static final Clock NOW = Clock.fixed(
        Instant.parse("2026-11-22T05:00:00Z"), ZoneOffset.UTC);
    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    private static final String CE = "NSE_FO|NIFTY_20261127_125_CE";
    private static final String PE = "NSE_FO|NIFTY_20261127_75_PE";
    private static final String INDEX = "NSE_INDEX|Nifty 50";
    private static final String PROVIDER = "UPSTOX_ANALYTICS";

    private final FnoVerifiedNiftyLongOptionSourceStore source =
        mock(FnoVerifiedNiftyLongOptionSourceStore.class);
    private final MarketSessionCalendar calendar = mock(MarketSessionCalendar.class);

    private Fno181182VerifiedHistoricalReplayService service(
            boolean index, boolean option, boolean listing) {
        return new Fno181182VerifiedHistoricalReplayService(
            source, calendar, NOW, index, option, listing);
    }

    private static List<FnoVerifiedNiftyLongOptionSourceStore.VerifiedClose>
            closes(String last) {
        var dates = new ArrayList<LocalDate>();
        var day = LocalDate.of(2026,11,19);
        while (dates.size() < 35) {
            if (day.getDayOfWeek().getValue() <= 5) dates.add(0,day);
            day=day.minusDays(1);
        }
        var result = new ArrayList<FnoVerifiedNiftyLongOptionSourceStore.VerifiedClose>();
        for (int i=0; i<dates.size(); i++) {
            Instant end=dates.get(i).atTime(15,30).atZone(IST).toInstant();
            result.add(new FnoVerifiedNiftyLongOptionSourceStore.VerifiedClose(
                PROVIDER,INDEX,dates.get(i),
                new BigDecimal(i==34 ? last : "100"),
                end.plusSeconds(120),end));
        }
        return List.copyOf(result);
    }

    private static FnoVerifiedNiftyLongOptionSourceStore.OptionProof quote(boolean call) {
        return new FnoVerifiedNiftyLongOptionSourceStore.OptionProof(
            PROVIDER,INDEX,call?CE:PE,call?"CE":"PE",
            LocalDate.of(2026,11,1),LocalDate.of(2026,11,27),
            50,new BigDecimal(call?"125":"75"),new BigDecimal("0.05"),
            new BigDecimal(call?"125":"75"),
            AS_OF.minusSeconds(40),AS_OF.minusSeconds(30),
            new BigDecimal("1.95"),new BigDecimal("2.00"),10,1000,
            AS_OF.minusSeconds(40),AS_OF.minusSeconds(30),
            call?500:1000,50,new BigDecimal("10"),10000,300,
            1000,2,50,new BigDecimal("10000"),3000,"a".repeat(64));
    }

    private void available(String latest) {
        when(calendar.isInsideCashSession(AS_OF)).thenReturn(true);
        when(calendar.isTradingDay(any(LocalDate.class))).thenAnswer(call ->
            ((LocalDate)call.getArgument(0)).getDayOfWeek().getValue() <=5);
        when(source.verifiedRecentCloses(AS_OF)).thenReturn(closes(latest));
        when(source.oneCurrentOption(CE,"CE",AS_OF))
            .thenReturn(Optional.of(quote(true)));
        when(source.oneCurrentOption(PE,"PE",AS_OF))
            .thenReturn(Optional.of(quote(false)));
    }

    @Test
    void actualLongCallAndLongPutEvaluatorsAreInvokedForOriginalSourceWindows() {
        available("125");
        var call=service(true,true,true).inspect("strategy.STR-181",CE,AS_OF);
        var put=service(true,true,true).inspect("strategy.STR-182",PE,AS_OF);
        assertEquals("ORIGINAL_SOURCE_RESEARCH_EVALUATOR_RAN_NOT_E2E",call.state());
        assertEquals("LONG_CALL_BULLISH_SCENARIO_RESEARCH_ONLY",call.reasonCode());
        assertEquals("ORIGINAL_SOURCE_RESEARCH_EVALUATOR_RAN_NOT_E2E",put.state());
        assertEquals("LONG_PUT_NEW_BEARISH_CROSSOVER_ABSENT",put.reasonCode());
        for (var result : List.of(call,put)) {
            assertEquals(35,result.verifiedIndexSessions());
            assertEquals(1,result.verifiedOptionQuotes());
            assertTrue(result.originalRightsJoinedInput());
            assertTrue(result.evaluatorInvoked());
            assertFalse(result.independentHistoricalE2E());
            assertFalse(result.paperLedgerE2E());
            assertFalse(result.liveBrokerE2E());
            assertFalse(result.brokerOrdersPlaced());
            assertFalse(result.toString().contains("NIFTY_20261127"));
        }
        verify(source).oneCurrentOption(CE,"CE",AS_OF);
        verify(source).oneCurrentOption(PE,"PE",AS_OF);
    }

    @Test
    void originalBearishHistoryActuallyInvokesDistinctLongPut() {
        available("75");
        var put=service(true,true,true).inspect("strategy.STR-182",PE,AS_OF);
        var call=service(true,true,true).inspect("strategy.STR-181",CE,AS_OF);
        assertEquals("LONG_PUT_BEARISH_SCENARIO_RESEARCH_ONLY",put.reasonCode());
        assertEquals("LONG_CALL_NEW_BULLISH_CROSSOVER_ABSENT",call.reasonCode());
        assertFalse(put.independentHistoricalE2E());
    }

    @Test
    void everyIndependentResearchRightsBitBlocksBeforeAnySourceRead() {
        for (int denied=0;denied<3;denied++) {
            var bits=new boolean[]{true,true,true};
            bits[denied]=false;
            var r=service(bits[0],bits[1],bits[2])
                .inspect("strategy.STR-181",CE,AS_OF);
            assertEquals("ORIGINAL_INDEX_OPTION_SOURCE_RIGHTS_NOT_VERIFIED",r.reasonCode());
            assertFalse(r.evaluatorInvoked());
        }
        verifyNoInteractions(source,calendar);
    }

    @Test
    void malformedFutureOrWeekendRequestsNeverReadPrivateSource() {
        var svc=service(true,true,true);
        for (String key : List.of("NSE_EQ|BAD","NSE_FO|../../etc","")) {
            assertEquals("HISTORICAL_NIFTY_OPTION_ASOF_SCOPE_INVALID",
                svc.inspect("strategy.STR-181",key,AS_OF).reasonCode());
        }
        assertEquals("HISTORICAL_NIFTY_OPTION_ASOF_SCOPE_INVALID",
            svc.inspect("strategy.STR-182",PE,NOW.instant().plusSeconds(1)).reasonCode());
        assertEquals("HISTORICAL_OPTION_STRATEGY_UNSUPPORTED",
            svc.inspect("strategy.STR-183",CE,AS_OF).reasonCode());
        verifyNoInteractions(source);
    }

    @Test
    void missingCompletedIndexSessionsMustNotFetchOptions() {
        available("125");
        when(source.verifiedRecentCloses(AS_OF))
            .thenReturn(closes("125").subList(0,34));
        var r=service(true,true,true).inspect("strategy.STR-181",CE,AS_OF);
        assertEquals("ORIGINAL_35_COMPLETED_NIFTY_SESSIONS_MISSING",r.reasonCode());
        assertEquals(0,r.verifiedOptionQuotes());
        verify(source,never()).oneCurrentOption(any(),any(),any());
    }

    @Test
    void missingOptionOrCrossProviderContractRejectsIndependentReplay() {
        available("125");
        when(source.oneCurrentOption(CE,"CE",AS_OF)).thenReturn(Optional.empty());
        assertEquals("ORIGINAL_OPTION_QUOTE_DEPTH_OI_COSTS_MISSING",
            service(true,true,true).inspect("strategy.STR-181",CE,AS_OF).reasonCode());
        var q=quote(true);
        var crossProvider=new FnoVerifiedNiftyLongOptionSourceStore.OptionProof(
            "ANGEL_ONE_SMARTAPI",q.indexKey(),q.optionKey(),q.optionType(),
            q.listedFrom(),q.expiry(),q.lotSize(),q.strike(),q.tickSize(),
            q.indexPrice(),q.indexExchangeAt(),q.indexFirstObservedAt(),
            q.optionBid(),q.optionAsk(),q.askDepthLots(),q.openInterestLots(),
            q.optionExchangeAt(),q.optionFirstObservedAt(),
            q.hypotheticalMoveAtExpiryBps(),q.buySlippageBps(),
            q.allInFeesPerLotInr(),q.minScenarioRewardRiskBps(),
            q.maxOptionSpreadBps(),q.maxStrikeDistanceBps(),
            q.minAskDepthLots(),q.minOpenInterestLots(),q.researchCapitalInr(),
            q.maxCapitalAllocationBps(),q.contractMasterSha256());
        when(source.oneCurrentOption(CE,"CE",AS_OF))
            .thenReturn(Optional.of(crossProvider));
        var r=service(true,true,true).inspect("strategy.STR-181",CE,AS_OF);
        assertEquals("OPTION_ORIGINAL_PROVIDER_CONTRACT_TIME_MISMATCH",r.reasonCode());
        assertFalse(r.evaluatorInvoked());
    }

    @Test
    void quotesNotFirstKnownByHistoricalDecisionCannotCreateResearch() {
        available("125");
        var q=quote(true);
        var late=new FnoVerifiedNiftyLongOptionSourceStore.OptionProof(
            q.provider(),q.indexKey(),q.optionKey(),q.optionType(),
            q.listedFrom(),q.expiry(),q.lotSize(),q.strike(),q.tickSize(),
            q.indexPrice(),q.indexExchangeAt(),q.indexFirstObservedAt(),
            q.optionBid(),q.optionAsk(),q.askDepthLots(),q.openInterestLots(),
            q.optionExchangeAt(),AS_OF.plusSeconds(1),
            q.hypotheticalMoveAtExpiryBps(),q.buySlippageBps(),
            q.allInFeesPerLotInr(),q.minScenarioRewardRiskBps(),
            q.maxOptionSpreadBps(),q.maxStrikeDistanceBps(),
            q.minAskDepthLots(),q.minOpenInterestLots(),q.researchCapitalInr(),
            q.maxCapitalAllocationBps(),q.contractMasterSha256());
        when(source.oneCurrentOption(CE,"CE",AS_OF))
            .thenReturn(Optional.of(late));
        var r=service(true,true,true).inspect("strategy.STR-181",CE,AS_OF);
        assertEquals("OPTION_ORIGINAL_PROVIDER_CONTRACT_TIME_MISMATCH",r.reasonCode());
        assertFalse(r.evaluatorInvoked());
    }

    @Test
    void SQLAndProviderErrorsRedactedWithoutClaimingE2ESuccess() {
        available("125");
        when(source.verifiedRecentCloses(AS_OF))
            .thenThrow(new IllegalStateException("private broker token"));
        var result=service(true,true,true).inspect("strategy.STR-181",CE,AS_OF);
        assertEquals("ORIGINAL_NIFTY_DAILY_SOURCE_UNAVAILABLE",result.reasonCode());
        assertFalse(result.toString().contains("broker token"));
        assertFalse(result.independentHistoricalE2E());
    }
}
