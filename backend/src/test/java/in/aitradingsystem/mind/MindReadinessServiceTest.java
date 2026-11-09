package in.aitradingsystem.mind;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import in.aitradingsystem.marketdata.MarketOverviewService;
import in.aitradingsystem.scanner.ResearchScanRepository;
import in.aitradingsystem.scanner.ResearchScanSummary;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;

class MindReadinessServiceTest {
    private static final Instant NOW = Instant.parse("2026-11-09T09:00:00Z");
    private final MarketOverviewService market = mock(MarketOverviewService.class);
    private final ResearchScanRepository scans = mock(ResearchScanRepository.class);
    private final MindReadinessService service = new MindReadinessService(
        market, scans, Clock.fixed(NOW, ZoneOffset.UTC));

    private static MarketOverviewService.Overview stored(Instant at) {
        return new MarketOverviewService.Overview(
            NOW, "STORED_LAST_OVERVIEW", "NIFTY50_CONSTITUENTS", at,
            new MarketOverviewService.Universe("NIFTY50", LocalDate.of(2026, 11, 9),
                50, 48, 40),
            new MarketOverviewService.Breadth(0, 0, 0, 0),
            List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of());
    }

    private static ResearchScanSummary run(String status, Instant completed) {
        return new ResearchScanSummary(UUID.randomUUID(), "FIVE_MINUTE",
            status, 100, 80, 5, "baseline-v1",
            completed.minusSeconds(30), completed, null, null);
    }

    @Test
    void missingSourcesAbstainWithoutInventingValuesOrEnablingOrders() {
        when(market.storedOverview()).thenReturn(Optional.empty());
        when(scans.latestCompletedRun()).thenReturn(Optional.empty());

        var result = service.inspect();
        assertEquals("mind.overview.v1", result.contractVersion());
        assertEquals(NOW, result.generatedAt());
        assertEquals("UNAVAILABLE", result.market().status());
        assertEquals("UNAVAILABLE", result.research().status());
        assertNull(result.market().asOf());
        assertNull(result.research().source());
        assertEquals("ABSTAIN", result.decisionPosture());
        assertFalse(result.verifiedSignal());
        assertFalse(result.executionEnabled());
    }

    @Test
    void persistedEvidenceIsLabelledStoredAndResearchNotExecutable() {
        when(market.storedOverview()).thenReturn(Optional.of(stored(NOW.minusSeconds(300))));
        when(scans.latestCompletedRun()).thenReturn(
            Optional.of(run("SUCCEEDED", NOW.minusSeconds(600))));
        var result = service.inspect();
        assertEquals("STORED", result.market().status());
        assertEquals("STORED_LAST_OVERVIEW", result.market().source());
        assertEquals(40, result.market().observations());
        assertEquals("RECORDED", result.research().status());
        assertEquals(80, result.research().observations());
        assertEquals("RESEARCH_NOT_VALIDATED_FOR_EXECUTION", result.research().reasonCode());
        assertEquals("ABSTAIN", result.decisionPosture());
        assertFalse(result.executionEnabled());
    }

    @Test
    void oldOrPartialScanNeverBecomesFreshDecisionEvidence() {
        when(market.storedOverview()).thenReturn(
            Optional.of(stored(NOW.minusSeconds(2 * 86400))));
        when(scans.latestCompletedRun()).thenReturn(
            Optional.of(run("PARTIAL", NOW.minusSeconds(120))));
        var result = service.inspect();
        assertEquals("STALE", result.market().status());
        assertEquals("PARTIAL", result.research().status());
        assertEquals(0, result.research().observations());
        assertFalse(result.verifiedSignal());
    }

    @Test
    void unreadableScanStoreDoesNotExposeStaleCachedOrSyntheticResults() {
        when(scans.latestCompletedRun()).thenThrow(new DataAccessResourceFailureException("database"));
        var result = service.inspect();
        assertEquals("UNAVAILABLE", result.research().status());
        assertEquals("RESEARCH_STORE_UNAVAILABLE", result.research().reasonCode());
        assertEquals("ABSTAIN", result.decisionPosture());
    }

    @Test
    void futureSourceTimestampIsRejected() {
        when(market.storedOverview()).thenReturn(
            Optional.of(stored(NOW.plusSeconds(600))));
        when(scans.latestCompletedRun()).thenReturn(
            Optional.of(run("SUCCEEDED", NOW.plusSeconds(60))));
        var result = service.inspect();
        assertEquals("UNAVAILABLE", result.market().status());
        assertEquals("UNAVAILABLE", result.research().status());
    }
}
