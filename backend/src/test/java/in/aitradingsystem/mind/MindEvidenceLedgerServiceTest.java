package in.aitradingsystem.mind;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.*;

import in.aitradingsystem.scanner.ResearchScanCandidateView;
import in.aitradingsystem.scanner.ResearchScanDiagnostic;
import in.aitradingsystem.scanner.ResearchScanRepository;
import in.aitradingsystem.scanner.ResearchScanSummary;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;

class MindEvidenceLedgerServiceTest {
    private static final Instant NOW = Instant.parse("2026-11-09T09:00:00Z");
    private final ResearchScanRepository scans = mock(ResearchScanRepository.class);
    private final MindEvidenceLedgerService service =
        new MindEvidenceLedgerService(scans, Clock.fixed(NOW, ZoneOffset.UTC));
    private final UUID runId = UUID.randomUUID();

    private ResearchScanSummary run(String status, Instant completedAt) {
        return new ResearchScanSummary(runId, "FIVE_MINUTE",
            status, 100, 80, 4, "baseline-v1",
            completedAt.minusSeconds(60), completedAt, null, null);
    }

    private ResearchScanCandidateView candidate(boolean lineage) {
        return new ResearchScanCandidateView(
            runId, 42, "INFY-EQ", "NSE", "FIVE_MINUTE", "MA_RECLAIM", 0.82,
            new BigDecimal("1500"), new BigDecimal("1490"), new BigDecimal("1520"),
            NOW.minusSeconds(1200), List.of("TREND_CONFIRMED"),
            "sha256-candidate-fingerprint",
            lineage ? 2 : null,
            lineage ? UUID.randomUUID() : null,
            NOW.minusSeconds(1100), 7);
    }

    @Test
    void absentRunNeverBuildsCandidatesOrAuthorizesOrders() {
        when(scans.latestCompletedRun()).thenReturn(Optional.empty());
        var result = service.inspect(20);
        assertEquals("UNAVAILABLE", result.sourceStatus());
        assertNull(result.scanRunId());
        assertEquals(List.of(), result.entries());
        assertEquals("ABSTAIN", result.decisionPosture());
        assertFalse(result.verifiedSignal());
        assertFalse(result.executionEnabled());
        verify(scans, never()).rankedCandidates(any(), anyInt());
    }

    @Test
    void persistedCandidateExposesOnlyHistoryAndNeverPricesOrOrders() {
        var run = run("SUCCEEDED", NOW.minusSeconds(100));
        when(scans.latestCompletedRun()).thenReturn(Optional.of(run));
        when(scans.rankedCandidates(runId, 20)).thenReturn(List.of(candidate(true)));

        var ledger = service.inspect(20);
        assertEquals("RECORDED", ledger.sourceStatus());
        assertEquals(1, ledger.entries().size());
        assertEquals("RESEARCH_ONLY", ledger.entries().getFirst().usability());
        assertEquals("NOT_INDEPENDENTLY_VERIFIED",
            ledger.entries().getFirst().knownAtStatus());
        assertEquals("NSE:INFY-EQ", ledger.entries().getFirst().instrument());
        assertEquals("sha256-candidate-fingerprint", ledger.entries().getFirst().fingerprint());
        assertFalse(ledger.entries().getFirst().executable());
        assertFalse(ledger.executionEnabled());
        assertEquals("ABSTAIN", ledger.decisionPosture());
    }

    @Test
    void incompleteCandidateLineageIsWithheldAndCounted() {
        when(scans.latestCompletedRun()).thenReturn(
            Optional.of(run("SUCCEEDED", NOW.minusSeconds(100))));
        when(scans.rankedCandidates(runId, 20)).thenReturn(List.of(candidate(false)));

        var ledger = service.inspect(20);
        assertEquals(1, ledger.rejectedCandidates());
        assertEquals(List.of(), ledger.entries());
        assertEquals(true, ledger.blockingReasons().contains("INCOMPLETE_CANDIDATE_PROVENANCE"));
    }

    @Test
    void partialRunShowsOnlyBoundedDiagnosticCodesNotCandidateSignals() {
        when(scans.latestCompletedRun()).thenReturn(
            Optional.of(run("PARTIAL", NOW.minusSeconds(150))));
        when(scans.recentDiagnostics(runId, 20)).thenReturn(List.of(
            new ResearchScanDiagnostic(12, runId, 42L, "MA_RECLAIM", "WARN",
                "MISSING_CANDLES", "private data details not allowed",
                NOW.minusSeconds(120))));
        var ledger = service.inspect(20);

        assertEquals("PARTIAL", ledger.sourceStatus());
        assertEquals("RESEARCH_DIAGNOSTIC", ledger.entries().getFirst().kind());
        assertEquals("MISSING_CANDLES", ledger.entries().getFirst().referenceCode());
        assertNull(ledger.entries().getFirst().fingerprint());
        assertNull(ledger.entries().getFirst().dataCutoffAt());
        assertEquals("ABSTAIN", ledger.decisionPosture());
        verify(scans, never()).rankedCandidates(any(), anyInt());
    }

    @Test
    void staleRunNeverAppearsAsFreshOrAuthorized() {
        when(scans.latestCompletedRun()).thenReturn(
            Optional.of(run("SUCCEEDED", NOW.minusSeconds(48 * 3600))));
        when(scans.rankedCandidates(runId, 20)).thenReturn(List.of());
        var result = service.inspect(20);
        assertEquals("STALE", result.sourceStatus());
        assertEquals(true, result.blockingReasons().contains("RESEARCH_SCAN_STALE"));
        assertFalse(result.executionEnabled());
    }

    @Test
    void databaseFailureIsAnUnavailableLedgerNotFabricatedRows() {
        when(scans.latestCompletedRun()).thenThrow(
            new DataAccessResourceFailureException("internal table unavailable"));
        var result = service.inspect(20);
        assertEquals("UNAVAILABLE", result.sourceStatus());
        assertNull(result.source());
        assertEquals(List.of(), result.entries());
        assertEquals(List.of("RESEARCH_LEDGER_UNAVAILABLE"), result.blockingReasons());
    }

    @Test
    void futureTimestampCannotBeRetoldAsResearchHistory() {
        when(scans.latestCompletedRun()).thenReturn(
            Optional.of(run("SUCCEEDED", NOW.plusSeconds(300))));
        var result = service.inspect(20);
        assertEquals("UNAVAILABLE", result.sourceStatus());
        assertEquals(List.of(), result.entries());
    }

    @Test
    void resultLimitsAreEnforcedBeforeDatabaseRead() {
        org.junit.jupiter.api.Assertions.assertThrows(
            IllegalArgumentException.class, () -> service.inspect(31));
        verifyNoInteractions(scans);
    }
}
