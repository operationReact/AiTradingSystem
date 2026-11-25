package in.aitradingsystem.tradingrooms;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import in.aitradingsystem.marketdata.FnoVerifiedStrategy203206SourceStore;
import in.aitradingsystem.marketdata.FnoVerifiedStrategy207212SourceStore;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * Model-data tests only. A mocked license receipt must NEVER turn into
 * independently authenticated NSE history, broker paper ledger, or live fills.
 */
class Fno43RuntimeSourceAuditServiceTest {
    private static final Instant NOW=Instant.parse("2026-11-21T10:00:00Z");

    private static Fno43StrategyReadinessService mockedCoverage(int direct){
        var readiness=mock(Fno43StrategyReadinessService.class);
        var rows=new ArrayList<Fno43StrategyReadinessService.StrategyRow>();
        for(int i=170;i<=212;i++){
            boolean observed=i<170+direct;
            rows.add(new Fno43StrategyReadinessService.StrategyRow(
                "strategy.STR-"+i,"Strategy "+i,
                "FnoDeterministicV1.java",List.of("source original first known"),
                observed?"DIRECT_EVALUATOR_OBSERVED":"DIRECT_ADAPTER_UNAVAILABLE",
                "BLOCKED_RIGHTS","SOURCE_RESEARCH_RIGHTS_DENIED",
                NOW,"NONE",0,0,0,observed,false,false,false,false));
        }
        var coverage=new Fno43StrategyReadinessService.Coverage(
            "minttick.43.v1",NOW,43,direct,0,43-direct,0,0,0,0,List.copyOf(rows));
        when(readiness.inspect(NOW)).thenReturn(coverage);
        return readiness;
    }
    private static Fno43RuntimeSourceAuditService audit(
        Fno43StrategyReadinessService readiness,
        FnoVerifiedStrategy203206SourceStore early,
        FnoVerifiedStrategy207212SourceStore late){
        return new Fno43RuntimeSourceAuditService(readiness,early,late);
    }

    @Test void all43ResearchAdaptersCanExistWithZeroRealMarketProof(){
        var early=mock(FnoVerifiedStrategy203206SourceStore.class);
        var late=mock(FnoVerifiedStrategy207212SourceStore.class);
        var report=audit(mockedCoverage(43),early,late).inspect(NOW);
        assertEquals(43,report.strategies().size());
        assertEquals(43,report.distinctResearchAdaptersObserved());
        assertEquals(0,report.strategiesWithoutAdapters());
        assertEquals(10,report.originalEnvelopeSourceFamiliesAudited());
        assertEquals(0,report.eligibleRightsAttestedEnvelopes());
        assertEquals(10,report.missingOrUnlicensedOriginalEnvelopes());
        assertEquals(0,report.independentMarketE2EValidated());
        assertEquals(0,report.paperLedgerE2EValidated());
        assertEquals(0,report.liveBrokerE2EValidated());
        assertFalse(report.brokerOrdersSubmitted());
        assertFalse(report.productionDeploymentVerified());
        assertTrue(report.strategies().stream().allMatch(x->
            !x.independentlyVerifiedMarketE2E() && !x.paperLedgerE2E()
            && !x.liveBrokerE2E()));
        assertEquals("NOT_AUDITED_BY_THIS_SOURCE_FAMILY",
            report.strategies().get(32).sourceEvidenceGate());
        verify(early,times(4)).oneCurrent(anyString(),eq(NOW));
        verify(late,times(6)).oneCurrent(anyString(),eq(NOW));
    }

    @Test void twoRightsAttestedEnvelopesStillProveNoMarketOrPaperBrokerExecution(){
        var early=mock(FnoVerifiedStrategy203206SourceStore.class);
        var late=mock(FnoVerifiedStrategy207212SourceStore.class);
        when(early.oneCurrent("strategy.STR-203",NOW)).thenReturn(Optional.of(
            new FnoVerifiedStrategy203206SourceStore.Proof(
                "strategy.STR-203","UPSTOX_ANALYTICS","{}",NOW.minusSeconds(30),"a".repeat(64))));
        when(late.oneCurrent("strategy.STR-212",NOW)).thenReturn(Optional.of(
            new FnoVerifiedStrategy207212SourceStore.Proof(
                "strategy.STR-212","ANGEL_ONE_SMARTAPI","{}",NOW.minusSeconds(30),"a".repeat(64))));
        var report=audit(mockedCoverage(43),early,late).inspect(NOW);
        assertEquals(2,report.eligibleRightsAttestedEnvelopes());
        assertEquals(8,report.missingOrUnlicensedOriginalEnvelopes());
        assertEquals("RIGHTS_ATTESTED_CURRENT_ENVELOPE_ONLY",
            report.strategies().get(33).sourceEvidenceGate());
        assertEquals("RIGHTS_ATTESTED_CURRENT_ENVELOPE_ONLY",
            report.strategies().get(42).sourceEvidenceGate());
        assertEquals(0,report.independentMarketE2EValidated());
        assertEquals(0,report.paperLedgerE2EValidated());
        assertEquals(0,report.liveBrokerE2EValidated());
    }

    @Test void sourceQueryExceptionIsRedactedAndDoesNotMakeReady(){
        var early=mock(FnoVerifiedStrategy203206SourceStore.class);
        var late=mock(FnoVerifiedStrategy207212SourceStore.class);
        when(late.oneCurrent("strategy.STR-209",NOW)).thenThrow(
            new IllegalStateException("sensitive SSM broker token"));
        var report=audit(mockedCoverage(43),early,late).inspect(NOW);
        assertEquals(10,report.missingOrUnlicensedOriginalEnvelopes());
        assertEquals("SOURCE_QUERY_FAILED_NO_ATTESTATION",
            report.strategies().get(39).sourceEvidenceGate());
        assertFalse(report.toString().contains("token"));
        assertEquals(0,report.independentMarketE2EValidated());
    }

    @Test void missingOrCorruptedReadinessNeverFabricatesDirectCallsites(){
        var readiness=mock(Fno43StrategyReadinessService.class);
        when(readiness.inspect(NOW)).thenThrow(new IllegalStateException("private broker error"));
        var report=audit(readiness,mock(FnoVerifiedStrategy203206SourceStore.class),
            mock(FnoVerifiedStrategy207212SourceStore.class)).inspect(NOW);
        assertEquals(0,report.distinctResearchAdaptersObserved());
        assertEquals(43,report.strategiesWithoutAdapters());
        assertEquals("READINESS_NOT_OBSERVED",report.strategies().getFirst().integrationState());
        assertFalse(report.toString().contains("private broker error"));
    }

    @Test void triplicateStrategyIdNeverRestoresFalseResearchCoverage(){
        var readiness=mock(Fno43StrategyReadinessService.class);
        var valid=mockedCoverage(43).inspect(NOW).strategies().getFirst();
        // Three entries with identical IDs must NOT toggle a missing ID back
        // to present after the second entry invalidates it.
        var duplicated=List.of(valid,valid,valid);
        var coverage=new Fno43StrategyReadinessService.Coverage(
            "minttick.43.v1",NOW,43,43,0,0,0,0,0,0,duplicated);
        when(readiness.inspect(NOW)).thenReturn(coverage);
        var report=audit(readiness,mock(FnoVerifiedStrategy203206SourceStore.class),
            mock(FnoVerifiedStrategy207212SourceStore.class)).inspect(NOW);
        assertEquals(0,report.distinctResearchAdaptersObserved());
        assertEquals(43,report.strategiesWithoutAdapters());
        assertEquals("READINESS_NOT_OBSERVED",report.strategies().getFirst().integrationState());
        assertEquals(0,report.independentMarketE2EValidated());
    }

    @Test void noCacheAndRoleRestrictedOperatorEndpoint() throws Exception {
        var route=AdminFno43SourceEvidenceController.class
            .getDeclaredMethod("evidence");
        var auth=route.getAnnotation(
            org.springframework.security.access.prepost.PreAuthorize.class);
        assertNotNull(auth);
        assertEquals("hasAnyRole('ADMIN', 'OPERATOR')",auth.value());
        var now=java.time.Clock.fixed(NOW,java.time.ZoneOffset.UTC);
        var mocked=mock(Fno43RuntimeSourceAuditService.class);
        var controller=new AdminFno43SourceEvidenceController(mocked,now);
        var response=controller.evidence();
        assertNotNull(response.getHeaders().getCacheControl());
        assertTrue(response.getHeaders().getCacheControl().contains("no-store"));
        verify(mocked).inspect(NOW);
    }
}
