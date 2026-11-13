package in.aitradingsystem.research;

import java.time.Instant;

/**
 * Fail-closed, source-neutral research evidence check. This class grants no
 * permission to retain, train, display, or execute using provider data.
 */
public final class CanonicalResearchEvidenceGate {
    private CanonicalResearchEvidenceGate() {}

    public record Evidence(String provider, String listingId, String interval,
                           String datasetVersion, Instant acquiredAt,
                           Instant availableAt, Instant decisionAt,
                           boolean listingVerified, boolean coverageVerified,
                           boolean qualityVerified, boolean usageRightsVerified) {}

    public record Decision(boolean researchEligible, String reason,
                           boolean paperExecutionAuthorized) {}

    public static Decision evaluate(Evidence evidence) {
        if (evidence == null) return reject("missing_evidence");
        if (blank(evidence.provider()) || blank(evidence.listingId())
                || blank(evidence.interval()) || blank(evidence.datasetVersion())) {
            return reject("missing_lineage");
        }
        if (evidence.acquiredAt() == null || evidence.availableAt() == null
                || evidence.decisionAt() == null) return reject("missing_time");
        if (evidence.availableAt().isAfter(evidence.decisionAt())
                || evidence.acquiredAt().isAfter(evidence.decisionAt())) {
            return reject("lookahead");
        }
        if (!evidence.listingVerified()) return reject("unverified_listing");
        if (!evidence.coverageVerified() || !evidence.qualityVerified()) {
            return reject("unverified_quality_or_coverage");
        }
        if (!evidence.usageRightsVerified()) return reject("usage_rights_unverified");
        return new Decision(true, "research_only", false);
    }

    private static boolean blank(String s) { return s == null || s.isBlank(); }
    private static Decision reject(String reason) { return new Decision(false, reason, false); }
}
