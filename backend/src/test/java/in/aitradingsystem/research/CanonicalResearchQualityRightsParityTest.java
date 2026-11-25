package in.aitradingsystem.research;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import org.junit.jupiter.api.Test;

/** Synthetic, broker-neutral research gate parity; never uses quotes or places orders. */
class CanonicalResearchQualityRightsParityTest {
    private static final Instant AT = Instant.parse("2026-11-13T04:00:00Z");

    private static CanonicalResearchEvidenceGate.Evidence evidence(
            String provider, boolean listing, boolean coverage, boolean quality,
            boolean rights) {
        return new CanonicalResearchEvidenceGate.Evidence(
                provider, "NSE:LISTING:42", "ONE_MINUTE", "dataset-v2",
                AT, AT, AT, listing, coverage, quality, rights);
    }

    private static void assertDecision(
            CanonicalResearchEvidenceGate.Evidence evidence,
            boolean eligible, String reason) {
        var decision = CanonicalResearchEvidenceGate.evaluate(evidence);
        assertThat(decision.researchEligible()).isEqualTo(eligible);
        assertThat(decision.reason()).isEqualTo(reason);
        assertThat(decision.paperExecutionAuthorized()).isFalse();
    }

    @Test
    void verificationFlagsFailClosedWithIdenticalProviderOutcomes() {
        for (String provider : new String[] {"ANGEL_ONE", "UPSTOX_ANALYTICS"}) {
            assertDecision(evidence(provider, true, true, true, true),
                    true, "research_only");
            assertDecision(evidence(provider, false, true, true, true),
                    false, "unverified_listing");
            assertDecision(evidence(provider, true, false, true, true),
                    false, "unverified_quality_or_coverage");
            assertDecision(evidence(provider, true, true, false, true),
                    false, "unverified_quality_or_coverage");
            assertDecision(evidence(provider, true, true, true, false),
                    false, "usage_rights_unverified");
        }
    }

    @Test
    void verifiedRightsCannotOverrideUnverifiedCoverageOrListing() {
        for (String provider : new String[] {"ANGEL_ONE", "UPSTOX_ANALYTICS"}) {
            assertDecision(evidence(provider, false, false, false, true),
                    false, "unverified_listing");
            assertDecision(evidence(provider, true, false, false, true),
                    false, "unverified_quality_or_coverage");
            assertDecision(evidence(provider, true, true, false, false),
                    false, "unverified_quality_or_coverage");
        }
    }
}
