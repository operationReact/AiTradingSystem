package in.aitradingsystem.research;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import org.junit.jupiter.api.Test;

/** Synthetic research-only boundary checks for the canonical, broker-neutral evidence gate. */
class CanonicalResearchEvidenceBoundaryTest {
    private static final Instant DECISION = Instant.parse("2026-11-13T03:45:00Z");

    private static CanonicalResearchEvidenceGate.Evidence evidence(
            String provider, String listing, String interval, String version,
            Instant acquired, Instant available, Instant decision,
            boolean listingVerified, boolean coverageVerified,
            boolean qualityVerified, boolean rightsVerified) {
        return new CanonicalResearchEvidenceGate.Evidence(
                provider, listing, interval, version, acquired, available, decision,
                listingVerified, coverageVerified, qualityVerified, rightsVerified);
    }

    private static CanonicalResearchEvidenceGate.Evidence valid(String provider) {
        return evidence(provider, "NSE:LISTING:42", "ONE_MINUTE", "dataset-v2",
                DECISION.minusSeconds(1), DECISION.minusSeconds(2), DECISION,
                true, true, true, true);
    }

    private static void rejects(CanonicalResearchEvidenceGate.Evidence evidence, String reason) {
        var result = CanonicalResearchEvidenceGate.evaluate(evidence);
        assertThat(result.researchEligible()).isFalse();
        assertThat(result.reason()).isEqualTo(reason);
        assertThat(result.paperExecutionAuthorized()).isFalse();
    }

    @Test
    void exactDecisionTimeIsResearchOnlyForEitherSource() {
        for (String provider : new String[] {"ANGEL_ONE", "UPSTOX_ANALYTICS"}) {
            var source = valid(provider);
            var atBoundary = evidence(provider, source.listingId(), source.interval(),
                    source.datasetVersion(), DECISION, DECISION, DECISION,
                    true, true, true, true);
            var result = CanonicalResearchEvidenceGate.evaluate(atBoundary);
            assertThat(result.researchEligible()).isTrue();
            assertThat(result.reason()).isEqualTo("research_only");
            assertThat(result.paperExecutionAuthorized()).isFalse();
        }
    }

    @Test
    void missingCanonicalIdentityIntervalOrVersionFailsClosed() {
        var source = valid("ANGEL_ONE");
        for (String missing : new String[] {null, "", "  "}) {
            rejects(evidence(source.provider(), missing, source.interval(),
                    source.datasetVersion(), source.acquiredAt(), source.availableAt(),
                    DECISION, true, true, true, true), "missing_lineage");
            rejects(evidence(source.provider(), source.listingId(), missing,
                    source.datasetVersion(), source.acquiredAt(), source.availableAt(),
                    DECISION, true, true, true, true), "missing_lineage");
            rejects(evidence(source.provider(), source.listingId(), source.interval(),
                    missing, source.acquiredAt(), source.availableAt(),
                    DECISION, true, true, true, true), "missing_lineage");
        }
    }

    @Test
    void missingTimesAndOneNanosecondLookaheadFailClosed() {
        var source = valid("UPSTOX_ANALYTICS");
        rejects(evidence(source.provider(), source.listingId(), source.interval(),
                source.datasetVersion(), null, source.availableAt(), DECISION,
                true, true, true, true), "missing_time");
        rejects(evidence(source.provider(), source.listingId(), source.interval(),
                source.datasetVersion(), source.acquiredAt(), null, DECISION,
                true, true, true, true), "missing_time");
        rejects(evidence(source.provider(), source.listingId(), source.interval(),
                source.datasetVersion(), source.acquiredAt(), source.availableAt(), null,
                true, true, true, true), "missing_time");
        rejects(evidence(source.provider(), source.listingId(), source.interval(),
                source.datasetVersion(), DECISION.plusNanos(1), source.availableAt(),
                DECISION, true, true, true, true), "lookahead");
        rejects(evidence(source.provider(), source.listingId(), source.interval(),
                source.datasetVersion(), source.acquiredAt(), DECISION.plusNanos(1),
                DECISION, true, true, true, true), "lookahead");
    }

    @Test
    void rightsQualityAndCoverageNeverGrantPaperAuthority() {
        var source = valid("ANGEL_ONE");
        rejects(evidence(source.provider(), source.listingId(), source.interval(),
                source.datasetVersion(), source.acquiredAt(), source.availableAt(),
                DECISION, true, false, true, true), "unverified_quality_or_coverage");
        rejects(evidence(source.provider(), source.listingId(), source.interval(),
                source.datasetVersion(), source.acquiredAt(), source.availableAt(),
                DECISION, true, true, false, true), "unverified_quality_or_coverage");
        rejects(evidence(source.provider(), source.listingId(), source.interval(),
                source.datasetVersion(), source.acquiredAt(), source.availableAt(),
                DECISION, true, true, true, false), "usage_rights_unverified");
    }
}
