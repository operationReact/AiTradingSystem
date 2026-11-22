package in.aitradingsystem.tradingrooms;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Synthetic only: exercises original typed source-backed STR-183..186 routes. */
class Fno183186VerifiedHistoricalReplayServiceTest {
    private static final Instant FIRST=Instant.parse("2026-11-20T05:00:00Z");
    private static final Instant SECOND=Instant.parse("2026-11-23T05:00:00Z");
    private static final Clock CLOCK=Clock.fixed(
        Instant.parse("2026-11-23T07:00:00Z"),ZoneOffset.UTC);
    private static final String[] KEYS={
        "NSE_FO|D_BULL_LOWER_CE","NSE_FO|D_BULL_UPPER_CE",
        "NSE_FO|D_BEAR_LOWER_PE","NSE_FO|D_BEAR_UPPER_PE",
        "NSE_FO|C_BULL_LOWER_PE","NSE_FO|C_BULL_UPPER_PE",
        "NSE_FO|C_BEAR_LOWER_CE","NSE_FO|C_BEAR_UPPER_CE"};
    private final FnoDebitSpreadResearchService debit=
        mock(FnoDebitSpreadResearchService.class);
    private final FnoCreditSpreadResearchService credit=
        mock(FnoCreditSpreadResearchService.class);

    private Fno183186VerifiedHistoricalReplayService service(){
        return new Fno183186VerifiedHistoricalReplayService(
            debit,credit,CLOCK,KEYS[0],KEYS[1],KEYS[2],KEYS[3],
            KEYS[4],KEYS[5],KEYS[6],KEYS[7]);
    }
    private static String id(int n){return "strategy.STR-"+n;}
    private static String primary(int n){
        return switch(n){
            case 183->KEYS[0];case 184->KEYS[3];
            case 185->KEYS[5];case 186->KEYS[6];
            default->throw new IllegalArgumentException();
        };
    }
    private static String originalState(int n){
        return switch(n){
            case 183->"VERIFIED_TWO_LEG_CE_DEBIT_SPREAD_RESEARCH_NO_ORDER";
            case 184->"VERIFIED_TWO_LEG_PE_DEBIT_SPREAD_RESEARCH_NO_ORDER";
            case 185->"VERIFIED_TWO_LEG_PE_CREDIT_SPREAD_RESEARCH_NO_ORDER";
            case 186->"VERIFIED_TWO_LEG_CE_CREDIT_SPREAD_RESEARCH_NO_ORDER";
            default->throw new IllegalArgumentException();
        };
    }
    private static FnoFuturesResearchService.StrategyStatus result(
            int n,Instant at,String state,int bars,String evaluation){
        return new FnoFuturesResearchService.StrategyStatus(
            id(n),evaluation,"RESEARCH_SOURCE_EVALUATOR_RESULT",at,
            "UPSTOX_ANALYTICS",2,3,bars,state);
    }
    @Test
    void allFourGenuineTypedSpreadPathsAreRoutedWithoutBrokerOrders(){
        for(int n=183;n<=186;n++){
            var evaluator=n<185?debit:credit;
            when(evaluator.evaluateOne(id(n),FIRST)).thenReturn(
                result(n,FIRST,""+originalState(n),35,"RESEARCH_CANDIDATE"));
            var proof=service().inspect(id(n),primary(n),FIRST);
            assertEquals("ORIGINAL_TWO_LEG_EVALUATOR_RESEARCH_ONLY",proof.state());
            assertEquals(35,proof.verifiedCloses());
            assertEquals(3,proof.originalLegQuotes());
            assertEquals("RESEARCH_CANDIDATE",proof.evaluatorStatus());
            assertFalse(proof.independentHistoricalE2E());
            assertFalse(proof.paperLedgerE2E());
            assertFalse(proof.liveBrokerE2E());
            assertFalse(proof.brokerOrdersPlaced());
            for(String key:KEYS) assertFalse(proof.toString().contains(key));
            verify(evaluator).evaluateOne(id(n),FIRST);
        }
    }
    @Test
    void primaryLegAndAllOtherLegsMustBeFixedAndDistinct(){
        var svc=service();
        for(int n=183;n<=186;n++){
            String wrong=n==183?KEYS[1]:KEYS[0];
            var denial=svc.inspect(id(n),wrong,FIRST);
            assertEquals("HISTORICAL_SPREAD_SOURCE_SCOPE_INVALID",denial.reason());
            assertEquals("NOT_RUN",denial.evaluatorStatus());
        }
        assertEquals("HISTORICAL_SPREAD_STRATEGY_UNSUPPORTED",
            svc.inspect(id(190),primary(183),FIRST).reason());
        assertEquals("HISTORICAL_SPREAD_SOURCE_SCOPE_INVALID",
            svc.inspect(id(183),primary(183),CLOCK.instant().plusSeconds(1)).reason());
        verifyNoInteractions(debit,credit);
    }
    @Test
    void fabricatedSourceInputLabelOrInsufficientSessionsNeverAccepted(){
        when(debit.evaluateOne(id(183),FIRST)).thenReturn(
            result(183,FIRST,"SYNTHETIC_QUOTES",35,"RESEARCH_CANDIDATE"));
        assertEquals("ORIGINAL_TWO_LEG_SOURCE_OR_RISK_GATES_UNMET",
            service().inspect(id(183),primary(183),FIRST).reason());
        when(debit.evaluateOne(id(183),FIRST)).thenReturn(
            result(183,FIRST,originalState(183),34,"RESEARCH_CANDIDATE"));
        assertEquals("BLOCKED_NOT_HISTORICAL_E2E",
            service().inspect(id(183),primary(183),FIRST).state());
        when(credit.evaluateOne(id(186),FIRST)).thenReturn(
            result(186,FIRST,originalState(186),35,"BLOCKED_DATA"));
        assertEquals("BLOCKED_NOT_HISTORICAL_E2E",
            service().inspect(id(186),primary(186),FIRST).state());
    }
    @Test
    void originalProviderExceptionsAreRedacted(){
        when(credit.evaluateOne(id(185),FIRST)).thenThrow(
            new IllegalStateException("secret credit-account margin token"));
        var proof=service().inspect(id(185),primary(185),FIRST);
        assertEquals("ORIGINAL_TWO_LEG_SOURCE_UNAVAILABLE",proof.reason());
        assertFalse(proof.toString().contains("token"));
    }
    @Test
    void monotonicallyIncreasingWindowsHaveStableDigestNotE2EApproval(){
        when(debit.evaluateOne(id(184),FIRST)).thenReturn(
            result(184,FIRST,originalState(184),35,"RESEARCH_CANDIDATE"));
        when(debit.evaluateOne(id(184),SECOND)).thenReturn(
            result(184,SECOND,originalState(184),40,"NO_SIGNAL"));
        var dates=List.of(FIRST,SECOND);
        var a=service().replay(id(184),primary(184),dates);
        var b=service().replay(id(184),primary(184),dates);
        assertEquals(a,b);
        assertEquals("EVALUATORS_RAN_RESEARCH_ONLY_NOT_E2E",a.state());
        assertEquals(2,a.windows());assertEquals(1,a.candidates());
        assertEquals(1,a.noSignals());assertEquals(0,a.blocked());
        assertEquals(64,a.runSha256().length());
        assertFalse(a.independentHistoricalE2E());
        assertFalse(a.brokerOrdersPlaced());
    }
    @Test
    void unavailableOriginalWindowIsCountedBlockedNotInvented(){
        when(credit.evaluateOne(id(186),FIRST)).thenReturn(
            result(186,FIRST,originalState(186),35,"NO_SIGNAL"));
        var r=service().replay(id(186),primary(186),List.of(FIRST,SECOND));
        assertEquals("INCOMPLETE_ORIGINAL_SPREAD_HISTORY",r.state());
        assertEquals(1,r.noSignals());assertEquals(1,r.blocked());
        assertFalse(r.liveBrokerE2E());
    }
    @Test
    void duplicatesOutOfOrderTooManyWindowsOrFutureDecisionFailClosed(){
        var svc=service();
        for(var dates:List.of(List.of(FIRST,FIRST),List.of(SECOND,FIRST),
                List.of(CLOCK.instant().plusSeconds(1)))){
            assertEquals("BLOCKED_NOT_HISTORICAL_E2E",
                svc.replay(id(183),primary(183),dates).state());
        }
        assertEquals("REPLAY_WINDOW_SCOPE_INVALID",
            svc.replay(id(183),primary(183),
                java.util.Collections.nCopies(65,FIRST)).reason());
        verifyNoInteractions(debit,credit);
    }
}
