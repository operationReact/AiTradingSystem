package in.aitradingsystem.research;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import org.junit.jupiter.api.Test;

class CanonicalResearchEvidenceGateTest {
    private static final Instant DECISION = Instant.parse("2026-11-12T05:00:00Z");

    private static CanonicalResearchEvidenceGate.Evidence evidence(String provider) {
        return new CanonicalResearchEvidenceGate.Evidence(
                provider, "NSE:LISTING:42", "ONE_MINUTE", "v1",
                DECISION.minusSeconds(30), DECISION.minusSeconds(60), DECISION,
                true, true, true, true);
    }

    @Test
    void equivalentProviderEvidenceUsesSameGateWithoutExecutionAuthority() {
        for (String provider : new String[] {"ANGEL_ONE", "UPSTOX_ANALYTICS"}) {
            var result = CanonicalResearchEvidenceGate.evaluate(evidence(provider));
            assertThat(result.researchEligible()).isTrue();
            assertThat(result.reason()).isEqualTo("research_only");
            assertThat(result.paperExecutionAuthorized()).isFalse();
        }
    }

    @Test
    void rejectsFutureAvailabilityAndAcquisition() {
        var source = evidence("UPSTOX_ANALYTICS");
        var futureAvailable = new CanonicalResearchEvidenceGate.Evidence(
                source.provider(), source.listingId(), source.interval(), source.datasetVersion(),
                source.acquiredAt(), DECISION.plusSeconds(1), DECISION,
                true, true, true, true);
        assertThat(CanonicalResearchEvidenceGate.evaluate(futureAvailable).reason())
                .isEqualTo("lookahead");
        var futureAcquired = new CanonicalResearchEvidenceGate.Evidence(
                source.provider(), source.listingId(), source.interval(), source.datasetVersion(),
                DECISION.plusSeconds(1), source.availableAt(), DECISION,
                true, true, true, true);
        assertThat(CanonicalResearchEvidenceGate.evaluate(futureAcquired).researchEligible()).isFalse();
    }

    @Test
    void rejectsMissingLineageQualityMappingAndUsageRights() {
        var source = evidence("ANGEL_ONE");
        assertThat(CanonicalResearchEvidenceGate.evaluate(null).researchEligible()).isFalse();
        assertThat(CanonicalResearchEvidenceGate.evaluate(evidence(" ")).reason())
                .isEqualTo("missing_lineage");
        assertThat(CanonicalResearchEvidenceGate.evaluate(new CanonicalResearchEvidenceGate.Evidence(
                source.provider(), source.listingId(), source.interval(), source.datasetVersion(),
                source.acquiredAt(), source.availableAt(), DECISION,
                false, true, true, true)).reason()).isEqualTo("unverified_listing");
        assertThat(CanonicalResearchEvidenceGate.evaluate(new CanonicalResearchEvidenceGate.Evidence(
                source.provider(), source.listingId(), source.interval(), source.datasetVersion(),
                source.acquiredAt(), source.availableAt(), DECISION,
                true, false, true, true)).researchEligible()).isFalse();
        assertThat(CanonicalResearchEvidenceGate.evaluate(new CanonicalResearchEvidenceGate.Evidence(
                source.provider(), source.listingId(), source.interval(), source.datasetVersion(),
                source.acquiredAt(), source.availableAt(), DECISION,
                true, true, true, false)).reason()).isEqualTo("usage_rights_unverified");
    }
}
