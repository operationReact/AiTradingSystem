package in.aitradingsystem.mind;

import in.aitradingsystem.marketdata.MarketOverviewService;
import in.aitradingsystem.scanner.ResearchScanRepository;
import in.aitradingsystem.scanner.ResearchScanSummary;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;

/**
 * Read-only, user-independent market/research evidence for Mintick Mind.
 * Does not score strategies, access user portfolios, or grant order authority.
 * Demo fixtures are deliberately excluded from this service.
 */
@Service
public class MindReadinessService {
    private static final Duration MAX_RECORDED_AGE = Duration.ofHours(24);
    private final MarketOverviewService market;
    private final ResearchScanRepository scans;
    private final Clock clock;

    public record Evidence(String status, String source, Instant asOf,
                           int observations, String reasonCode) {
        public static Evidence unavailable(String reason) {
            return new Evidence("UNAVAILABLE", null, null, 0, reason);
        }
    }

    public record Overview(String contractVersion, Instant generatedAt,
                           Evidence market, Evidence research,
                           String decisionPosture, boolean verifiedSignal,
                           boolean executionEnabled) {}

    public MindReadinessService(MarketOverviewService market, ResearchScanRepository scans, Clock clock) {
        this.market = market;
        this.scans = scans;
        this.clock = clock;
    }

    public Overview inspect() {
        Instant now = clock.instant();
        // An existing scan is evidence that research ran, not proof of a
        // calibrated prediction or a trading recommendation.
        return new Overview("mind.overview.v1", now, marketEvidence(now),
            researchEvidence(now), "ABSTAIN", false, false);
    }

    private Evidence marketEvidence(Instant now) {
        try {
            var stored = market.storedOverview();
            if (stored.isEmpty()) return Evidence.unavailable("NO_VERIFIED_MARKET_SNAPSHOT");
            var snapshot = stored.get();
            Instant asOf = snapshot.exchangeAsOf();
            if (asOf == null || asOf.isAfter(now)) {
                return Evidence.unavailable("MARKET_TIMESTAMP_UNVERIFIED");
            }
            int observations = snapshot.universe() == null
                ? 0 : Math.max(0, snapshot.universe().returned());
            boolean old = asOf.isBefore(now.minus(MAX_RECORDED_AGE));
            return new Evidence(old ? "STALE" : "STORED",
                "STORED_LAST_OVERVIEW", asOf, observations,
                old ? "MARKET_SNAPSHOT_STALE" : "STORED_NOT_LIVE");
        } catch (DataAccessException | IllegalStateException exception) {
            return Evidence.unavailable("MARKET_SNAPSHOT_UNAVAILABLE");
        }
    }

    private Evidence researchEvidence(Instant now) {
        Optional<ResearchScanSummary> result;
        try {
            result = scans.latestCompletedRun();
        } catch (DataAccessException exception) {
            return Evidence.unavailable("RESEARCH_STORE_UNAVAILABLE");
        }
        if (result.isEmpty()) return Evidence.unavailable("NO_COMPLETED_RESEARCH_SCAN");
        ResearchScanSummary scan = result.get();
        Instant asOf = scan.completedAt();
        if (asOf == null || asOf.isAfter(now)) {
            return Evidence.unavailable("RESEARCH_TIMESTAMP_UNVERIFIED");
        }
        if (!"SUCCEEDED".equals(scan.status())) {
            return new Evidence("FAILED".equals(scan.status()) ? "FAILED" : "PARTIAL",
                "PERSISTED_RESEARCH_SCAN", asOf, 0, "SCAN_NOT_COMPLETE");
        }
        boolean old = asOf.isBefore(now.minus(MAX_RECORDED_AGE));
        return new Evidence(old ? "STALE" : "RECORDED", "PERSISTED_RESEARCH_SCAN",
            asOf, Math.max(0, scan.scannedCount()),
            old ? "RESEARCH_SCAN_STALE" : "RESEARCH_NOT_VALIDATED_FOR_EXECUTION");
    }
}
