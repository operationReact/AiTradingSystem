package in.aitradingsystem.tradingrooms;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.prepost.PreAuthorize;

class Fno43StrategyReadinessServiceTest {
    private static final Instant NOW = Instant.parse("2026-11-21T12:00:00Z");

    private static Fno43StrategyReadinessService create(FnoFuturesResearchService futures) {
        return new Fno43StrategyReadinessService(futures, new ObjectMapper());
    }

    @Test
    void exact43ManifestDefinitionsExposeDistinctInputContractsAndNeverPromoteReadiness() {
        var futures = mock(FnoFuturesResearchService.class);
        when(futures.evaluateAll(NOW)).thenReturn(List.of(
            new FnoFuturesResearchService.StrategyStatus("strategy.STR-170", "BLOCKED_DATA",
                "SPOT_NOT_VERIFIED", NOW, "NONE", 1, 0, 0, "NO_SPOT"),
            new FnoFuturesResearchService.StrategyStatus("strategy.STR-171", "BLOCKED_RIGHTS",
                "SOURCE_RIGHTS_NOT_VERIFIED", NOW, "NONE", 2, 0, 0, "PAIR_RESOLVED"),
            new FnoFuturesResearchService.StrategyStatus("strategy.STR-172", "BLOCKED_DATA",
                "HISTORY_INSUFFICIENT", NOW, "NONE", 2, 0, 1, "PAIR_RESOLVED"),
            new FnoFuturesResearchService.StrategyStatus("strategy.STR-173", "BLOCKED_RIGHTS",
                "OI_SOURCE_RIGHTS_NOT_VERIFIED", NOW, "NONE", 0, 0, 0, "OI_INPUTS_BLOCKED"),
            new FnoFuturesResearchService.StrategyStatus("strategy.STR-174", "BLOCKED_RIGHTS",
                "PRICE_OI_SOURCE_RIGHTS_NOT_VERIFIED", NOW, "NONE", 0, 0, 0, "PRICE_OI_FINAL_SOURCE_BLOCKED"),
            new FnoFuturesResearchService.StrategyStatus("strategy.STR-175", "BLOCKED_RIGHTS",
                "CASH_FUT_SOURCE_RIGHTS_NOT_VERIFIED", NOW, "NONE", 0, 0, 0, "CASH_FUT_FEASIBILITY_SOURCE_BLOCKED"),
            new FnoFuturesResearchService.StrategyStatus("strategy.STR-176", "BLOCKED_RIGHTS",
                "FUT_MOM_SOURCE_RIGHTS_NOT_VERIFIED", NOW, "NONE", 0, 0, 0, "FUT_MOM_SOURCE_BLOCKED"),
            new FnoFuturesResearchService.StrategyStatus("strategy.STR-177", "BLOCKED_RIGHTS",
                "FUT_MR_SOURCE_RIGHTS_NOT_VERIFIED", NOW, "NONE", 0, 0, 0, "FUT_MR_SOURCE_BLOCKED"),
            new FnoFuturesResearchService.StrategyStatus("strategy.STR-178", "BLOCKED_RIGHTS",
                "HEDGE_RESEARCH_RIGHTS_NOT_VERIFIED", NOW, "NONE", 0, 0, 0, "INDEX_HEDGE_SOURCE_BLOCKED"),
            new FnoFuturesResearchService.StrategyStatus("strategy.STR-179", "BLOCKED_RIGHTS",
                "LIQUIDITY_SOURCE_RIGHTS_NOT_VERIFIED", NOW, "NONE", 0, 0, 0, "LIQUIDITY_SOURCE_BLOCKED"),
            new FnoFuturesResearchService.StrategyStatus("strategy.STR-180", "BLOCKED_RIGHTS",
                "EXPIRY_BASIS_SOURCE_RIGHTS_MISSING", NOW, "NONE", 0, 0, 0, "EXPIRY_BASIS_VERIFIED_SOURCE_BLOCKED")));
        var report = create(futures).inspect(NOW);

        assertEquals(43, report.manifestStrategies());
        assertEquals(43, report.strategies().size());
        assertEquals(11, report.distinctEvaluatorsObserved());
        assertEquals(2, report.signalRoutesWithoutDistinctEvaluator());
        assertEquals(32, report.strategiesWithoutDirectEvaluatorAdapter());
        assertEquals(0, report.independentlyVerifiedSourceStrategies());
        assertEquals(0, report.historicalDataE2EValidated());
        assertEquals(0, report.paperExecutionValidated());
        assertEquals(0, report.liveExecutionValidated());
        assertEquals(43, report.strategies().stream().map(r -> r.strategyId()).distinct().count());
        assertEquals("strategy.STR-170", report.strategies().get(0).strategyId());
        assertEquals("strategy.STR-212", report.strategies().get(42).strategyId());
        assertTrue(report.strategies().stream().allMatch(r -> !r.requiredInputs().isEmpty()
            && r.evaluatorSource().endsWith(".java")
            && !r.independentlyVerifiedSourceRights()
            && !r.historicalDataE2EValidated()
            && !r.paperExecutionValidated() && !r.liveExecutionValidated()));
        assertEquals(11, report.strategies().stream()
            .filter(Fno43StrategyReadinessService.StrategyRow::distinctEvaluatorObserved).count());
        var byId = report.strategies().stream().collect(
            Collectors.toMap(r -> r.strategyId(), r -> r));
        assertEquals("SPOT_NOT_VERIFIED", byId.get("strategy.STR-170").reasonCode());
        assertEquals(1, byId.get("strategy.STR-170").contractsResolved());
        assertEquals("SIGNAL_ROUTE_ONLY", byId.get("strategy.STR-181").integrationState());
        assertFalse(byId.get("strategy.STR-181").distinctEvaluatorObserved());
        assertEquals("DISTINCT_EVALUATOR_NOT_INVOKED", byId.get("strategy.STR-182").reasonCode());
        assertEquals("NO_TYPED_SOURCE_ADAPTER", byId.get("strategy.STR-212").integrationState());
        assertEquals(Set.of("strategy.STR-170", "strategy.STR-171", "strategy.STR-172", "strategy.STR-173", "strategy.STR-174", "strategy.STR-175", "strategy.STR-176", "strategy.STR-177", "strategy.STR-178", "strategy.STR-179", "strategy.STR-180"),
            report.strategies().stream().filter(
                Fno43StrategyReadinessService.StrategyRow::distinctEvaluatorObserved)
                .map(Fno43StrategyReadinessService.StrategyRow::strategyId)
                .collect(Collectors.toSet()));
        verify(futures, times(1)).evaluateAll(NOW);
        verifyNoMoreInteractions(futures);
    }

    @Test
    void providerFailureDoesNotInventEvidenceOrLeakExceptionDetails() {
        var futures = mock(FnoFuturesResearchService.class);
        when(futures.evaluateAll(NOW)).thenThrow(new IllegalStateException("secret provider response"));
        var report = create(futures).inspect(NOW);
        assertEquals(43, report.strategies().size());
        assertEquals(0, report.distinctEvaluatorsObserved());
        assertEquals(11, report.strategies().stream()
            .filter(r -> "DIRECT_ADAPTER_UNAVAILABLE".equals(r.integrationState())).count());
        assertTrue(report.strategies().stream().noneMatch(r ->
            r.reasonCode().contains("secret") || r.distinctEvaluatorObserved()));
    }

    @Test
    void duplicateRuntimeEvidenceFailsClosed() {
        var futures = mock(FnoFuturesResearchService.class);
        var row = new FnoFuturesResearchService.StrategyStatus("strategy.STR-170",
            "RESEARCH_CANDIDATE", "MODEL_CANDIDATE", NOW, "TEST", 2, 2, 0, "INPUTS");
        when(futures.evaluateAll(NOW)).thenReturn(List.of(row, row));
        var report = create(futures).inspect(NOW);
        assertEquals(0, report.distinctEvaluatorsObserved());
        assertEquals(0, report.liveExecutionValidated());
    }

    @Test
    void evenResearchCandidateIsNotPaperOrLiveAuthorization() {
        var futures = mock(FnoFuturesResearchService.class);
        when(futures.evaluateAll(NOW)).thenReturn(List.of(
            new FnoFuturesResearchService.StrategyStatus("strategy.STR-171",
                "RESEARCH_CANDIDATE", "SOURCE_SYNCHRONIZED", NOW, "TEST", 2, 2, 0, "PAIR")));
        var report = create(futures).inspect(NOW);
        assertEquals(1, report.distinctEvaluatorsObserved());
        assertEquals(0, report.independentlyVerifiedSourceStrategies());
        assertFalse(report.strategies().get(1).paperExecutionValidated());
        assertFalse(report.strategies().get(1).liveExecutionValidated());
    }

    @Test
    void adminEndpointIsRoleRestrictedAndNoStore() throws Exception {
        var futures = mock(FnoFuturesResearchService.class);
        when(futures.evaluateAll(NOW)).thenReturn(List.of());
        var controller = new AdminFno43ReadinessController(
            create(futures), Clock.fixed(NOW, ZoneOffset.UTC));
        var response = controller.readiness();
        assertTrue(response.getHeaders().getCacheControl().contains("no-store"));
        assertEquals(NOW, response.getBody().generatedAt());
        assertEquals(43, response.getBody().manifestStrategies());
        var policy = AdminFno43ReadinessController.class.getMethod("readiness")
            .getAnnotation(PreAuthorize.class);
        assertNotNull(policy);
        assertEquals("hasAnyRole('ADMIN', 'OPERATOR')", policy.value());
    }
}
