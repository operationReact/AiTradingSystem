package in.aitradingsystem.mind;

import in.aitradingsystem.scanner.ResearchScanCandidateView;
import in.aitradingsystem.scanner.ResearchScanDiagnostic;
import in.aitradingsystem.scanner.ResearchScanRepository;
import in.aitradingsystem.scanner.ResearchScanSummary;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;

/**
 * Mintick Mind's first historical Evidence Ledger projection.
 *
 * A persisted scan/candidate is evidence that research code ran, not evidence
 * that the signal was profitable, point-in-time valid, or approved to trade.
 * No broker, user holding, order, demo fixture or model scoring dependency
 * exists here. The caller may only inspect the most recent completed run.
 */
@Service
public class MindEvidenceLedgerService {
    private static final Duration MAX_RESEARCH_AGE = Duration.ofHours(24);
    private final ResearchScanRepository scans;
    private final Clock clock;

    public MindEvidenceLedgerService(ResearchScanRepository scans, Clock clock) {
        this.scans = scans;
        this.clock = clock;
    }

    public record Entry(
        String id, String kind, String source, String instrument,
        String strategy, String referenceCode, Instant observedAt,
        Instant dataCutoffAt, String fingerprint, String usability,
        String knownAtStatus, boolean executable
    ) {}

    public record Ledger(
        String contractVersion, Instant generatedAt, String sourceStatus,
        UUID scanRunId, Instant scanCompletedAt, String source,
        List<Entry> entries, List<String> blockingReasons,
        int rejectedCandidates, String decisionPosture,
        boolean verifiedSignal, boolean executionEnabled
    ) {}

    public Ledger inspect(int limit) {
        if (limit < 1 || limit > 30) throw new IllegalArgumentException("limit must be 1..30");
        Instant now = clock.instant();
        try {
            var latest = scans.latestCompletedRun();
            if (latest.isEmpty()) {
                return result(now, "UNAVAILABLE", null, List.of(),
                    List.of("NO_COMPLETED_RESEARCH_SCAN"), 0);
            }
            ResearchScanSummary run = latest.get();
            if (run.completedAt() == null || run.completedAt().isAfter(now)) {
                return result(now, "UNAVAILABLE", null, List.of(),
                    List.of("RESEARCH_TIMESTAMP_UNVERIFIED"), 0);
            }
            String sourceStatus = switch (run.status()) {
                case "SUCCEEDED" -> run.completedAt().isBefore(now.minus(MAX_RESEARCH_AGE))
                    ? "STALE" : "RECORDED";
                case "PARTIAL" -> "PARTIAL";
                case "FAILED" -> "FAILED";
                default -> "UNAVAILABLE";
            };
            if (!"RECORDED".equals(sourceStatus) && !"STALE".equals(sourceStatus)) {
                // A newer failed/partial run must not be replaced by older winners.
                List<Entry> diagnostics = diagnostics(run.id(), limit, now);
                return result(now, sourceStatus, run, diagnostics,
                    List.of("LATEST_SCAN_NOT_SUCCESSFUL", "NO_VERIFIED_DECISION_EVIDENCE"), 0);
            }
            List<Entry> entries = new ArrayList<>();
            int rejected = 0;
            for (ResearchScanCandidateView candidate : scans.rankedCandidates(run.id(), limit)) {
                if (!validLineage(candidate, run, now)) {
                    rejected++;
                    continue;
                }
                entries.add(new Entry(
                    "candidate-" + candidate.candidateId(), "RESEARCH_CANDIDATE",
                    "PERSISTED_RESEARCH_SCAN_CANDIDATE", candidate.exchange()
                        + ":" + candidate.tradingSymbol(), candidate.strategy(),
                    "SCAN_CANDIDATE", candidate.candleTime(),
                    candidate.dataCutoffAt(), candidate.candidateFingerprint(),
                    "RESEARCH_ONLY", "NOT_INDEPENDENTLY_VERIFIED", false));
            }
            List<String> blockers = new ArrayList<>();
            blockers.add("NO_VERIFIED_DECISION_EVIDENCE");
            if ("STALE".equals(sourceStatus)) blockers.add("RESEARCH_SCAN_STALE");
            if (rejected > 0) blockers.add("INCOMPLETE_CANDIDATE_PROVENANCE");
            if (entries.isEmpty()) blockers.add("NO_ELIGIBLE_PROVENANCE_ROWS");
            return result(now, sourceStatus, run, entries, blockers, rejected);
        } catch (DataAccessException | IllegalStateException ex) {
            // Never expose SQL/provider errors or invent a successful scan.
            return result(now, "UNAVAILABLE", null, List.of(),
                List.of("RESEARCH_LEDGER_UNAVAILABLE"), 0);
        }
    }

    private List<Entry> diagnostics(UUID scanId, int limit, Instant now) {
        List<Entry> entries = new ArrayList<>();
        for (ResearchScanDiagnostic diagnostic : scans.recentDiagnostics(scanId, limit)) {
            Instant at = diagnostic.createdAt();
            if (at == null || at.isAfter(now)) continue;
            String code = diagnostic.code();
            if (code == null || code.isBlank()) continue;
            entries.add(new Entry(
                "diagnostic-" + diagnostic.id(), "RESEARCH_DIAGNOSTIC",
                "PERSISTED_RESEARCH_SCAN_DIAGNOSTIC", null,
                diagnostic.strategy(), code, at, null, null,
                "BLOCKER_ONLY", "NOT_INDEPENDENTLY_VERIFIED", false));
        }
        return List.copyOf(entries);
    }

    private static boolean validLineage(ResearchScanCandidateView c,
                                        ResearchScanSummary run, Instant now) {
        if (c == null || !run.id().equals(c.scanRunId()) ||
            c.candidateId() < 1 || c.instrumentId() < 1 ||
            c.exchange() == null || !java.util.Set.of("NSE", "BSE").contains(c.exchange()) ||
            c.tradingSymbol() == null || c.tradingSymbol().isBlank() ||
            c.strategy() == null || c.strategy().isBlank() ||
            c.candidateFingerprint() == null || c.candidateFingerprint().isBlank() ||
            c.catalogueVersion() == null ||
            c.strategyConfigVersion() == null || c.strategyConfigVersion() < 1 ||
            c.candleTime() == null || c.dataCutoffAt() == null) return false;
        // Source/strategy version identifiers must exist before showing a
        // candidate, but a deterministic fingerprint is not a signature.
        Instant end = run.completedAt();
        return !c.candleTime().isAfter(now) &&
            !c.dataCutoffAt().isAfter(now) &&
            !c.candleTime().isAfter(end) &&
            !c.dataCutoffAt().isAfter(end);
    }

    private static Ledger result(Instant now, String state,
                                 ResearchScanSummary run, List<Entry> entries,
                                 List<String> blockers, int rejected) {
        return new Ledger(
            "mind.evidence.v1", now, state,
            run == null ? null : run.id(),
            run == null ? null : run.completedAt(),
            run == null ? null : "PERSISTED_RESEARCH_SCAN",
            List.copyOf(entries), List.copyOf(blockers), rejected,
            "ABSTAIN", false, false);
    }
}
