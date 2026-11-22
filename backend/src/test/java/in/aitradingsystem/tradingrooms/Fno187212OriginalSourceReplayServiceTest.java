package in.aitradingsystem.tradingrooms;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import org.junit.jupiter.api.Test;

/**
 * Synthetic/mocked source-output test, not original market replay E2E.
 * The real 26 source research services have independent SQL provenance tests.
 */
class Fno187212OriginalSourceReplayServiceTest {
    private static final Instant FIRST=Instant.parse("2026-11-20T05:00:00Z");
    private static final Instant SECOND=Instant.parse("2026-11-23T05:00:00Z");
    private static final Clock NOW=Clock.fixed(
        Instant.parse("2026-11-23T07:00:00Z"),ZoneOffset.UTC);
    private static final List<String> SOURCE_STATES=List.of(
        "SETTLED_EQUITY_PROTECTIVE_PUT_RESEARCH_NO_ORDER",
        "SETTLED_EQUITY_COVERED_CALL_RESEARCH_NO_ORDER",
        "BROKER_SETTLED_EQUITY_COLLAR_SOURCE_RESEARCH_NO_ORDER",
        "CALL_RATIO_UNBOUNDED_TAIL_RESEARCH_NO_ORDER",
        "PUT_RATIO_FINITE_EXPIRY_TAIL_RESEARCH_NO_ORDER",
        "ORIGINAL_PER_LEG_IV_SOURCE_RESEARCH_ONLY_NO_ORDER",
        "ORIGINAL_PER_LEG_IV_SOURCE_RESEARCH_ONLY_NO_ORDER",
        "SHORT_STRADDLE_UNBOUNDED_TAIL_RESEARCH_NO_ORDER",
        "SHORT_STRANGLE_UNBOUNDED_TAIL_RESEARCH_NO_ORDER",
        "ORIGINAL_FOUR_LEG_SOURCE_DEFINED_EXPIRY_RISK_ONLY_NO_ORDER",
        "ORIGINAL_FOUR_LEG_SOURCE_DEFINED_EXPIRY_RISK_ONLY_NO_ORDER",
        "ORIGINAL_THREE_CALL_TWO_CENTER_SHORT_LOTS_NO_ORDER",
        "ORIGINAL_DIFFERENT_EXPIRIES_FAR_MARK_UNRESOLVED_NO_ORDER",
        "ORIGINAL_DIFFERENT_EXPIRIES_FAR_MARK_UNRESOLVED_NO_ORDER",
        "ORIGINAL_30_DAY_ATM_IV_200_SOURCE_ALIGNED_CANDLES_NO_ORDER",
        "ORIGINAL_OFFICIAL_EVENT_PRE_POST_30D_IV_OBSERVATION_RETROSPECTIVE_NO_ORDER",
        "ORIGINAL_GREEKS_FUTURES_GAMMA_PATH_RETROSPECTIVE_NO_FILLS",
        "BROKER_CONFIRMED_POSITIONS_ORIGINAL_DELTA_FUTURES_HYPOTHETICAL_NO_ORDER",
        "BROKER_VERIFIED_HOLDINGS_ORIGINAL_PORTFOLIO_BETA_PROXY_NO_ORDER",
        "BROKER_VERIFIED_HOLDINGS_ORIGINAL_TAIL_STRESS_HINDSIGHT_ONLY_NO_ORDER",
        "BROKER_VERIFIED_LONG_FUTURE_PUT_WING_SHORT_CALL_UNCAPPED_TAIL_NO_ORDER",
        "BROKER_EXISTING_VERTICAL_FOUR_LEG_ROLL_RESEARCH_NO_ACTUAL_FILLS",
        "OFFICIAL_EXPIRY_CUTOFF_COMPLETE_BROKER_BOOK_HYPOTHETICAL_CLOSE_NO_ORDER",
        "BROKER_HOLDINGS_THREE_ORIGINAL_TAIL_DAYS_TWO_PUTS_BASIS_RISK_UNMODELED",
        "COMPLETE_BROKER_OPTIONS_BOOK_SOURCE_GREEKS_BUY_ONLY_NOT_ORDERS",
        "SOURCE_ORIGINAL_DELTA_MATCHED_PUT_CALL_SKEW_UNCAPPED_DOWNSIDE_NO_ORDER"
    );

    private static FnoFuturesResearchService.StrategyStatus result(
            int id,Instant at,String sourceState,String provider,String status){
        return new FnoFuturesResearchService.StrategyStatus(
            "strategy.STR-"+id,status,"ORIGINAL_EVALUATOR_RESEARCH_NO_TRADE",
            at,provider,2,3,35,sourceState);
    }
    private static Map<Integer,Function<Instant,List<FnoFuturesResearchService.StrategyStatus>>>
            normalMap(){
        Map<Integer,Function<Instant,List<FnoFuturesResearchService.StrategyStatus>>> map=
            new HashMap<>();
        for(int i=187;i<=212;i++){
            final int n=i;
            map.put(n,at->List.of(result(n,at,SOURCE_STATES.get(n-187),
                "UPSTOX_ANALYTICS","RESEARCH_CANDIDATE")));
        }
        return map;
    }
    private static Fno187212OriginalSourceReplayService service(
            Map<Integer,Function<Instant,List<FnoFuturesResearchService.StrategyStatus>>> map){
        return new Fno187212OriginalSourceReplayService(map,NOW);
    }

    @Test
    void allTwentySixSourceFamiliesHaveDistinctTypedStrategyRouting(){
        assertEquals(26,SOURCE_STATES.size());
        var ids=Fno187212OriginalSourceReplayService.supportedStrategies();
        assertEquals(26,ids.size());
        assertEquals("strategy.STR-187",ids.getFirst());
        assertEquals("strategy.STR-212",ids.getLast());
        var runner=service(normalMap());
        for(int n=187;n<=212;n++){
            var proof=runner.inspect("strategy.STR-"+n,FIRST);
            assertEquals("SOURCE_RESEARCH_EVALUATOR_RAN_NOT_E2E",proof.state());
            assertEquals("RESEARCH_CANDIDATE",proof.evaluatorStatus());
            assertEquals(35,proof.originalCompletedSessions());
            assertEquals(3,proof.originalQuotesObserved());
            assertFalse(proof.independentlyValidatedHistoricalE2E());
            assertFalse(proof.paperLedgerE2E());
            assertFalse(proof.liveBrokerE2E());
            assertFalse(proof.ordersPlaced());
        }
    }

    @Test
    void originalSourceWithDifferentProviderOrWrongResearchStateIsBlocked(){
        var map=normalMap();
        map.put(194,at->List.of(result(194,at,"SHORT_STRADDLE_SOURCE_BLOCKED_NO_ORDER",
            "UPSTOX_ANALYTICS","RESEARCH_CANDIDATE")));
        map.put(207,at->List.of(result(207,at,SOURCE_STATES.get(207-187),
            "UNVERIFIED_SOURCE","RESEARCH_CANDIDATE")));
        var r=service(map);
        assertEquals("ORIGINAL_SOURCE_OR_EVALUATOR_EVIDENCE_UNVERIFIED",
            r.inspect("strategy.STR-194",FIRST).reasonCode());
        assertEquals("BLOCKED_NOT_HISTORICAL_E2E",
            r.inspect("strategy.STR-207",FIRST).state());
    }

    @Test
    void missingDuplicateOrMismatchedOriginalStrategyRowsFailClosed(){
        var map=normalMap();
        map.put(187,at->List.of());
        map.put(188,at->List.of(result(187,at,SOURCE_STATES.getFirst(),
            "UPSTOX_ANALYTICS","NO_SIGNAL")));
        map.put(189,at->List.of(
            result(189,at,SOURCE_STATES.get(2),"UPSTOX_ANALYTICS","NO_SIGNAL"),
            result(189,at,SOURCE_STATES.get(2),"UPSTOX_ANALYTICS","NO_SIGNAL")));
        var r=service(map);
        assertEquals("ORIGINAL_SOURCE_ROWS_MISSING_OR_AMBIGUOUS",
            r.inspect("strategy.STR-187",FIRST).reasonCode());
        assertEquals("ORIGINAL_SOURCE_STRATEGY_NOT_UNIQUE",
            r.inspect("strategy.STR-188",FIRST).reasonCode());
        assertEquals("ORIGINAL_SOURCE_STRATEGY_NOT_UNIQUE",
            r.inspect("strategy.STR-189",FIRST).reasonCode());
    }

    @Test
    void badScopeAndFutureDecisionNeverInvokeOriginalSource(){
        var counter=new AtomicInteger();
        var map=normalMap();
        map.put(203,at->{counter.incrementAndGet();
            return List.of(result(203,at,SOURCE_STATES.get(16),
                "UPSTOX_ANALYTICS","NO_SIGNAL"));});
        var r=service(map);
        for(String bad:List.of("strategy.STR-170","strategy.STR-186",
                "strategy.STR-213","strategy.STR-x","")) {
            assertEquals("STRATEGY_OUTSIDE_ORIGINAL_REPLAY_SCOPE",
                r.inspect(bad,FIRST).reasonCode());
        }
        assertEquals("HISTORICAL_DECISION_TIME_INVALID",
            r.inspect("strategy.STR-203",NOW.instant().plusSeconds(1)).reasonCode());
        assertEquals(0,counter.get());
        assertEquals("SOURCE_RESEARCH_EVALUATOR_RAN_NOT_E2E",
            r.inspect("strategy.STR-203",FIRST).state());
        assertEquals(1,counter.get());
    }

    @Test
    void fullOriginalSourceExceptionsStayPrivateAndCannotBeAccepted(){
        var map=normalMap();
        map.put(210,at->{throw new IllegalStateException("private account token margin");});
        var r=service(map).inspect("strategy.STR-210",FIRST);
        assertEquals("ORIGINAL_SOURCE_READ_OR_EVALUATOR_FAILED",r.reasonCode());
        assertFalse(r.toString().contains("account token"));
        assertFalse(r.ordersPlaced());
    }

    @Test
    void boundedChronologicalReplayIsRepeatableNotMarketE2E(){
        var runner=service(normalMap());
        var dates=List.of(FIRST,SECOND);
        var a=runner.replay("strategy.STR-192",dates);
        var b=runner.replay("strategy.STR-192",dates);
        assertEquals(a,b);
        assertEquals(2,a.windowsExamined());
        assertEquals(2,a.candidates());
        assertEquals(0,a.blocked());
        assertEquals(64,a.deterministicSummaryHash().length());
        assertEquals("ORIGINAL_SOURCE_REPLAY_RESEARCH_ONLY_NOT_E2E",a.state());
        assertFalse(a.independentlyValidatedHistoricalE2E());
        assertFalse(a.paperLedgerE2E());
        assertFalse(a.liveBrokerE2E());
        assertFalse(a.ordersPlaced());
    }

    @Test
    void outOfOrderDuplicateFutureAndUnboundedDecisionsAreBlocked(){
        var r=service(normalMap());
        for(var times:List.of(List.of(FIRST,FIRST),List.of(SECOND,FIRST),
                List.of(NOW.instant().plusSeconds(1)))) {
            assertEquals("BLOCKED_NOT_HISTORICAL_E2E",
                r.replay("strategy.STR-208",times).state());
        }
        assertEquals("HISTORICAL_WINDOW_COUNT_OUT_OF_SCOPE",
            r.replay("strategy.STR-208",
                java.util.Collections.nCopies(17,FIRST)).reasonCode());
        assertEquals("STRATEGY_OUTSIDE_ORIGINAL_REPLAY_SCOPE",
            r.replay("strategy.STR-170",List.of(FIRST)).reasonCode());
    }

    @Test
    void oneOriginalWindowNotVerifiedPreventsWholeReplayAcceptance(){
        var map=normalMap();
        map.put(212,at->at.equals(SECOND)?List.of():
            List.of(result(212,at,SOURCE_STATES.get(25),
                "UPSTOX_ANALYTICS","NO_SIGNAL")));
        var r=service(map).replay("strategy.STR-212",List.of(FIRST,SECOND));
        assertEquals("HISTORICAL_SOURCE_REPLAY_INCOMPLETE",r.state());
        assertEquals(1,r.noSignals());
        assertEquals(1,r.blocked());
        assertFalse(r.independentlyValidatedHistoricalE2E());
    }
}
