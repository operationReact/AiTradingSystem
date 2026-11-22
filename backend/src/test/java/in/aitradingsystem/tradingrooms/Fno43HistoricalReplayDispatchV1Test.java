package in.aitradingsystem.tradingrooms;

import static org.junit.jupiter.api.Assertions.*;

import in.aitradingsystem.tradingrooms.Fno43HistoricalReplayDispatchV1.*;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * ALL 43 here are concrete typed evaluator references with fictional JUnit
 * input objects. They are NOT independently verified historical-data replays,
 * performance validations, PAPER fills or live broker transactions.
 */
class Fno43HistoricalReplayDispatchV1Test {
    private static final Instant AS_OF = Instant.parse("2026-11-20T11:00:00Z");
    private static final Instant VERIFY = Instant.parse("2026-11-22T05:00:00Z");
    private static final String HASH = "a".repeat(64);
    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");

    private static List<InputEvidence> syntheticCustody(CaseSpec c) {
        return c.requiredInputs().stream().map(requirement ->
            new InputEvidence(requirement,
                Instant.parse("2026-11-19T10:45:00Z"),
                Instant.parse("2026-11-19T10:46:00Z"),
                Instant.parse("2026-11-19T10:47:00Z"),
                HASH, "b".repeat(64), true)).toList();
    }

    private static Window fixture(CaseSpec c) {
        return new Window(AS_OF,
            Arrays.asList(new Object[c.evaluatorArgumentCount()]),
            syntheticCustody(c));
    }

    private static ReplayReportAssertions _none() { return null; }
    private interface ReplayReportAssertions {}

    @Test
    void catalogContainsExactly43ContiguousUniqueRealStaticJavaEvaluators() throws Exception {
        List<CaseSpec> all = Fno43HistoricalReplayDispatchV1.catalog();
        assertEquals(43, all.size());
        assertEquals(165, all.stream().mapToInt(c -> c.requiredInputs().size()).sum());
        for (int i = 0; i < all.size(); i++) {
            CaseSpec c = all.get(i);
            assertEquals("strategy.STR-" + (170 + i), c.strategyId());
            assertTrue(c.evaluatorArgumentCount() >= 4);
            assertTrue(c.requiredInputs().size() >= 3);
            assertEquals(c.requiredInputs().size(),
                c.requiredInputs().stream().distinct().count());
            Class<?> impl = Class.forName("in.aitradingsystem.tradingrooms."+
                c.javaEvaluator());
            List<Method> methods = Arrays.stream(impl.getDeclaredMethods())
                .filter(m -> m.getName().equals("evaluate")
                    && Modifier.isStatic(m.getModifiers())
                    && m.getParameterCount() == c.evaluatorArgumentCount() + 1
                    && m.getParameterTypes()[m.getParameterCount()-1]
                        == Instant.class)
                .toList();
            assertEquals(1, methods.size(), c.strategyId());
            assertEquals("Decision", methods.getFirst().getReturnType().getSimpleName());
        }
    }

    @Test
    void all43DispatchThroughConcreteJavaCallsWithoutPaperOrLiveAuthority() {
        for (CaseSpec spec : Fno43HistoricalReplayDispatchV1.catalog()) {
            var report = Fno43HistoricalReplayDispatchV1.replay(
                spec.strategyId(), List.of(fixture(spec)), VERIFY);
            assertNotNull(report, spec.strategyId());
            assertTrue(report.state().equals("TYPED_EVALUATORS_RAN_WITH_BLOCKED_INPUTS")
                    || report.state().equals("BLOCKED_NOT_HISTORICAL_E2E"),
                spec.strategyId() + ":" + report.state());
            assertFalse(report.independentHistoricalE2E());
            assertFalse(report.paperLedgerE2E());
            assertFalse(report.liveBrokerE2E());
            assertFalse(report.brokerOrdersPlaced());
            assertEquals(0, report.researchCandidates());
        }
    }

    private static FnoFuturesMomentumV1.CompletedSession momentumBar(
            LocalDate date, String closing, long volume) {
        BigDecimal p = new BigDecimal(closing);
        Instant officialClose = date.atTime(LocalTime.of(15,40))
            .atZone(IST).toInstant();
        return new FnoFuturesMomentumV1.CompletedSession(
            date, true, "NSE_FO|FUT_DEC_176", "UPSTOX_ANALYTICS",
            p.subtract(BigDecimal.ONE),p.add(new BigDecimal(".5")),
            p.subtract(new BigDecimal("1.5")),p,true,
            officialClose.plusSeconds(150),volume,100000,
            officialClose.plusSeconds(900));
    }

    @Test
    void executesActualSTR176HistoricalCandidateAndDeterministicDigest() {
        CaseSpec c=Fno43HistoricalReplayDispatchV1.catalog().get(176-170);
        List<FnoFuturesMomentumV1.CompletedSession> bars = List.of(
            momentumBar(LocalDate.of(2026,11,11),"100",30000),
            momentumBar(LocalDate.of(2026,11,12),"101",30000),
            momentumBar(LocalDate.of(2026,11,13),"102",30000),
            momentumBar(LocalDate.of(2026,11,16),"103",30000),
            momentumBar(LocalDate.of(2026,11,17),"104",50000),
            momentumBar(LocalDate.of(2026,11,18),"105",50000),
            momentumBar(LocalDate.of(2026,11,19),"111",50000));
        var contract = new FnoFuturesMomentumV1.Contract(
            "NSE_FO|FUT_DEC_176","NSE_INDEX|Nifty 50",
            LocalDate.of(2026,11,1),LocalDate.of(2026,12,24),75,true);
        var rights = new FnoFuturesMomentumV1.SourceRights(true,true,true);
        var thresholds = new FnoFuturesMomentumV1.Thresholds(
            100,2,11000,2000,2500,800,20,30);
        var window=new Window(AS_OF,
            List.of(contract,bars,rights,thresholds),syntheticCustody(c));
        var first=Fno43HistoricalReplayDispatchV1.replay(c.strategyId(),
            List.of(window),VERIFY);
        var second=Fno43HistoricalReplayDispatchV1.replay(c.strategyId(),
            List.of(window),VERIFY);
        assertEquals(first,second);
        assertEquals("TYPED_EVALUATORS_RAN_RESEARCH_ONLY",first.state());
        assertEquals(1,first.windowsEvaluated());
        assertEquals(1,first.researchCandidates());
        assertEquals("RESEARCH_CANDIDATE",first.rows().getFirst().evaluatorStatus());
        assertEquals("FUT_MOM_UPWARD_BREAKOUT_OBSERVED",
            first.rows().getFirst().reasonCode());
        assertEquals(64,first.runSha256().length());
        assertEquals(64,first.rows().getFirst().evidenceHash().length());
        assertFalse(first.independentHistoricalE2E());
    }

    @Test
    void rejectsSourceRightsProofTimestampAfterHistoricalDecision() {
        CaseSpec c=Fno43HistoricalReplayDispatchV1.catalog().getFirst();
        var evidence=new ArrayList<>(syntheticCustody(c));
        var first=evidence.getFirst();
        evidence.set(0,new InputEvidence(first.manifestRequirement(),
            AS_OF.plusSeconds(1),first.originalCapturedAt(),
            first.originalRightsVerifiedAt(),HASH,"b".repeat(64),true));
        var window=new Window(AS_OF,fixture(c).typedArguments(),evidence);
        var report=Fno43HistoricalReplayDispatchV1.replay(c.strategyId(),
            List.of(window),VERIFY);
        assertEquals("MISSING_OR_FUTURE_KNOWN_INPUT_CUSTODY",report.reasonCode());
        assertEquals(0,report.windowsEvaluated());
        assertNull(report.runSha256());
    }

    @Test
    void rejectsRetrospectivelyCapturedResearchDataAndUnverifiedRights() {
        var spec=Fno43HistoricalReplayDispatchV1.catalog().get(195-170);
        for (int gate=0;gate<3;gate++) {
            var proof=new ArrayList<>(syntheticCustody(spec));
            var p=proof.getFirst();
            proof.set(0,new InputEvidence(
                p.manifestRequirement(),p.originalFirstKnownAt(),
                gate==0 ? AS_OF.plusSeconds(10):p.originalCapturedAt(),
                gate==1 ? AS_OF.plusSeconds(10):p.originalRightsVerifiedAt(),
                p.sourceSha256(),p.rightsReceiptSha256(),gate!=2));
            var result=Fno43HistoricalReplayDispatchV1.replay(spec.strategyId(),
                List.of(new Window(AS_OF,fixture(spec).typedArguments(),proof)),VERIFY);
            assertEquals("MISSING_OR_FUTURE_KNOWN_INPUT_CUSTODY",
                result.reasonCode());
        }
    }

    @Test
    void rejectsMissingOrSwappedPerStrategyManifestRequirement() {
        var c=Fno43HistoricalReplayDispatchV1.catalog().get(209-170);
        var proof=new ArrayList<>(syntheticCustody(c));
        var first=proof.get(0),second=proof.get(1);
        proof.set(0,second);proof.set(1,first);
        assertEquals("MISSING_OR_FUTURE_KNOWN_INPUT_CUSTODY",
            Fno43HistoricalReplayDispatchV1.replay(c.strategyId(),
                List.of(new Window(AS_OF,fixture(c).typedArguments(),proof)),
                VERIFY).reasonCode());
        assertEquals("AS_OF_OR_TYPED_ARGUMENT_SCOPE_INVALID",
            Fno43HistoricalReplayDispatchV1.replay(c.strategyId(),
                List.of(new Window(AS_OF,fixture(c).typedArguments(),
                    proof.subList(0,2))),VERIFY).reasonCode());
    }

    @Test
    void noFutureDecisionNonmonotonicReplayOrWrongArgumentArity() {
        var c=Fno43HistoricalReplayDispatchV1.catalog().get(210-170);
        var valid=fixture(c);
        assertEquals("AS_OF_OR_TYPED_ARGUMENT_SCOPE_INVALID",
            Fno43HistoricalReplayDispatchV1.replay(c.strategyId(),
                List.of(valid,valid),VERIFY).reasonCode());
        assertEquals("AS_OF_OR_TYPED_ARGUMENT_SCOPE_INVALID",
            Fno43HistoricalReplayDispatchV1.replay(c.strategyId(),
                List.of(new Window(VERIFY.plusSeconds(1),
                    valid.typedArguments(),valid.inputEvidence())),VERIFY)
                    .reasonCode());
        assertEquals("AS_OF_OR_TYPED_ARGUMENT_SCOPE_INVALID",
            Fno43HistoricalReplayDispatchV1.replay(c.strategyId(),
                List.of(new Window(AS_OF,List.of(),valid.inputEvidence())),
                VERIFY).reasonCode());
    }

    @Test
    void cannotPromoteUnrecognizedStrategyNoEvidenceOrMalformedTypedPayload() {
        assertEquals("UNDECLARED_STRATEGY_ID",
            Fno43HistoricalReplayDispatchV1.replay(
                "strategy.STR-213",List.of(),VERIFY).reasonCode());
        var c=Fno43HistoricalReplayDispatchV1.catalog().get(176-170);
        assertEquals("REPLAY_SCOPE_INVALID",
            Fno43HistoricalReplayDispatchV1.replay(c.strategyId(),
                List.of(),VERIFY).reasonCode());
        var wrong = new Window(AS_OF,
            List.of("NOT_A_GENUINE_FUTURES_CONTRACT","ALSO_WRONG",
                    "FAKE","INVALID"), syntheticCustody(c));
        var report=Fno43HistoricalReplayDispatchV1.replay(
            c.strategyId(),List.of(wrong),VERIFY);
        assertEquals("TYPED_INPUT_OR_EVALUATOR_FAILED_CLOSED",report.reasonCode());
        assertEquals(0,report.windowsEvaluated());
        assertFalse(report.brokerOrdersPlaced());
    }
}
