package in.aitradingsystem.tradingrooms;

import static org.junit.jupiter.api.Assertions.*;

import in.aitradingsystem.tradingrooms.FnoFuturesHistoricalReplayBridgeV1.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * FICTIONAL local fixtures exercise actual STR-173/176 evaluator wiring.
 * Never interpret these unit tests as independently verified market E2E.
 */
class FnoFuturesHistoricalReplayBridgeV1Test {
    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    private static final Instant AS_OF = Instant.parse("2026-11-20T11:00:00Z");
    private static final String OI = "strategy.STR-173";
    private static final String MOM = "strategy.STR-176";
    private static final String HASH = "a".repeat(64);
    private static final LocalDate[] DATES = {
        LocalDate.of(2026, 11, 11), LocalDate.of(2026, 11, 12),
        LocalDate.of(2026, 11, 13), LocalDate.of(2026, 11, 16),
        LocalDate.of(2026, 11, 17), LocalDate.of(2026, 11, 18),
        LocalDate.of(2026, 11, 19)
    };
    private static final ContractIdentity CONTRACT = new ContractIdentity(
        "NSE_FO|FUT_DEC_173", "NSE_INDEX|Nifty 50",
        LocalDate.of(2026, 11, 1), LocalDate.of(2026, 12, 24), 75, true);
    private static final EvidenceRights RIGHTS = new EvidenceRights(
        true, true, true, true, true, true);

    private static BigDecimal n(String s) { return new BigDecimal(s); }
    private static Instant close(LocalDate d) {
        return d.atTime(LocalTime.of(15, 40)).atZone(IST).toInstant();
    }
    private static HistoricalBar bar(int i) {
        LocalDate d = DATES[i];
        String price = new String[]{"100","101","102","103","104","105","111"}[i];
        BigDecimal p = n(price);
        long volume = i < 4 ? 30000L : 50000L;
        long oi = new long[]{100000, 105000, 110000, 116000, 122000, 130000, 140000}[i];
        return new HistoricalBar(d, CONTRACT.instrumentKey(),
            "UPSTOX_ANALYTICS", true, true,
            close(d).minusSeconds(30), close(d).plusSeconds(150),
            close(d).plusSeconds(900),
            p.subtract(n("1")), p.add(n("0.5")),
            p.subtract(n("1.5")), p, volume, oi);
    }
    private static List<HistoricalBar> bars() {
        var a = new ArrayList<HistoricalBar>();
        for (int i = 0; i < DATES.length; i++) a.add(bar(i));
        return List.copyOf(a);
    }
    private static List<HistoricalBar> change(List<HistoricalBar> bars,
                                               int index, HistoricalBar newBar) {
        var copy = new ArrayList<>(bars);
        copy.set(index,newBar);
        return List.copyOf(copy);
    }
    private static Window frame(String strategy) {
        var all = bars();
        return new Window(AS_OF, CONTRACT, RIGHTS, HASH,
            MOM.equals(strategy) ? all : all.subList(2, 7));
    }
    private static Window changed(Window old, List<HistoricalBar> rows) {
        return new Window(old.asOf(),old.contract(),old.rights(),
                          old.sourceReceiptSha256(),rows);
    }
    private static ReplayReport run(String strategy) {
        return FnoFuturesHistoricalReplayBridgeV1.replay(strategy,
            List.of(frame(strategy)));
    }

    @Test
    void invokesActualOiAndMomentumEvaluatorsAndHashesTheirResearchOutputs() {
        for (String strategy : List.of(OI, MOM)) {
            var r = run(strategy);
            assertEquals("EVALUATOR_REPLAY_RESEARCH_ONLY_NOT_E2E",r.state());
            assertEquals(1,r.windowsAttempted());
            assertEquals(1,r.windowsEvaluatorRan());
            assertEquals(1,r.researchCandidates());
            assertEquals(0,r.noSignals());
            assertEquals("RESEARCH_CANDIDATE",r.decisions().getFirst().result());
            assertEquals(strategy,r.decisions().getFirst().strategyId());
            assertEquals(64,r.outputSha256().length());
            assertEquals(r,run(strategy));
            assertFalse(r.independentlyVerifiedMarketE2E());
            assertFalse(r.paperExecutionReady());
            assertFalse(r.liveExecutionReady());
            assertFalse(r.brokerOrdersPlaced());
        }
    }

    @Test
    void noHistoricalSourceRightsOrCalendarProofAlwaysBlocks() {
        var original=frame(MOM);
        for (int denied=0;denied<6;denied++) {
            var bits=new boolean[]{true,true,true,true,true,true};
            bits[denied]=false;
            var deniedRights = new EvidenceRights(bits[0],bits[1],bits[2],
                bits[3],bits[4],bits[5]);
            var w=new Window(original.asOf(),original.contract(),
                deniedRights,HASH,original.bars());
            var r=FnoFuturesHistoricalReplayBridgeV1.replay(MOM,List.of(w));
            assertEquals("BLOCKED_NOT_E2E",r.state());
            assertEquals("SOURCE_RIGHTS_CUSTODY_OR_WINDOW_MISSING",r.reasonCode());
            assertEquals(0,r.windowsEvaluatorRan());
        }
    }

    @Test
    void originalFirstKnownAfterDecisionOrSameSessionLabelCannotReplay() {
        var original=frame(MOM);
        var last=original.bars().getLast();
        var future=new HistoricalBar(last.session(),last.instrumentKey(),
            last.provider(),last.calendarVerified(),last.ohlcvVerified(),
            last.closeQuoteAt(),last.sourceFirstKnownAt(),
            AS_OF.plusSeconds(1),last.open(),last.high(),last.low(),last.close(),
            last.volumeUnderlyingUnits(),last.oiUnderlyingUnits());
        var blocked=FnoFuturesHistoricalReplayBridgeV1.replay(MOM,List.of(
            changed(original,change(original.bars(),6,future))));
        assertEquals("INVALID_OR_FUTURE_KNOWN_SESSION",blocked.reasonCode());
        var sameSession=new Window(Instant.parse("2026-11-19T11:00:00Z"),
            original.contract(),original.rights(),HASH,original.bars());
        assertEquals("INVALID_OR_FUTURE_KNOWN_SESSION",
            FnoFuturesHistoricalReplayBridgeV1.replay(MOM,List.of(sameSession))
                .reasonCode());
    }

    @Test
    void outOfOrderDuplicateOrCrossProviderSessionsCannotContaminateReplay() {
        var original=frame(MOM);
        var duplicate=change(original.bars(),5,original.bars().get(4));
        assertEquals("INVALID_OR_FUTURE_KNOWN_SESSION",
            FnoFuturesHistoricalReplayBridgeV1.replay(MOM,
                List.of(changed(original,duplicate))).reasonCode());
        var last=original.bars().getLast();
        var other=new HistoricalBar(last.session(),last.instrumentKey(),
            "ANGEL_ONE_SMARTAPI",true,true,last.closeQuoteAt(),
            last.sourceFirstKnownAt(),last.oiFirstKnownAt(),last.open(),
            last.high(),last.low(),last.close(),last.volumeUnderlyingUnits(),
            last.oiUnderlyingUnits());
        assertEquals("INVALID_OR_FUTURE_KNOWN_SESSION",
            FnoFuturesHistoricalReplayBridgeV1.replay(MOM,List.of(
                changed(original,change(original.bars(),6,other)))).reasonCode());
    }

    @Test
    void actualEvaluatorRejectsInvalidOhlcAfterBridgePassesTimeline() {
        var original=frame(MOM);
        var last=original.bars().getLast();
        var corrupt=new HistoricalBar(last.session(),last.instrumentKey(),
            last.provider(),true,true,last.closeQuoteAt(),
            last.sourceFirstKnownAt(),last.oiFirstKnownAt(),last.open(),
            n("1"),last.low(),last.close(),last.volumeUnderlyingUnits(),
            last.oiUnderlyingUnits());
        var result=FnoFuturesHistoricalReplayBridgeV1.replay(MOM,List.of(
            changed(original,change(original.bars(),6,corrupt))));
        assertEquals("BLOCKED_NOT_E2E",result.state());
        assertEquals("EVALUATOR_FUT_MOM_COMPLETED_BAR_INVALID",result.reasonCode());
        assertEquals(0,result.researchCandidates());
    }

    @Test
    void matchingRepeatedAsOfAndContractRolloverFailClosed() {
        var original=frame(OI);
        var duplicate=FnoFuturesHistoricalReplayBridgeV1.replay(OI,
            List.of(original,original));
        assertEquals("REPLAY_DECISION_TIMES_NOT_INCREASING",duplicate.reasonCode());
        var next=new Window(AS_OF.plusSeconds(86400),
            new ContractIdentity("NSE_FO|OTHER",CONTRACT.underlyingKey(),
                CONTRACT.listedFrom(),CONTRACT.expiry(),75,true),
            RIGHTS,HASH,original.bars());
        assertEquals("CONTRACT_ROLLOVER_IN_SINGLE_REPLAY",
            FnoFuturesHistoricalReplayBridgeV1.replay(OI,
                List.of(original,next)).reasonCode());
    }

    @Test
    void multipleChronologicalResearchWindowsReproduceWithoutOrders() {
        var first=frame(MOM);
        var next=new Window(AS_OF.plusSeconds(86400),CONTRACT,RIGHTS,HASH,
            first.bars());
        var result=FnoFuturesHistoricalReplayBridgeV1.replay(MOM,
            List.of(first,next));
        assertEquals(2,result.windowsEvaluatorRan());
        assertEquals(2,result.researchCandidates());
        assertEquals(2,result.decisions().size());
        assertFalse(result.independentlyVerifiedMarketE2E());
    }

    @Test
    void unsupportedRemaining41AndMalformedInputNeverBecomeSuccess() {
        for (int i=170;i<=212;i++) {
            String id="strategy.STR-"+i;
            if (List.of(OI,MOM).contains(id)) continue;
            var r=FnoFuturesHistoricalReplayBridgeV1.replay(id,List.of(frame(MOM)));
            assertEquals("NO_TYPED_HISTORICAL_REPLAY_ADAPTER",r.reasonCode());
            assertEquals(0,r.windowsEvaluatorRan());
        }
        var broken=frame(MOM);
        var untrusted=new Window(AS_OF,CONTRACT,RIGHTS,"not sha256",
            broken.bars());
        assertEquals("SOURCE_RIGHTS_CUSTODY_OR_WINDOW_MISSING",
            FnoFuturesHistoricalReplayBridgeV1.replay(MOM,
                List.of(untrusted)).reasonCode());
    }
}
