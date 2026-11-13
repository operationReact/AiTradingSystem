package in.aitradingsystem.research;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import org.junit.jupiter.api.Test;

/** Synthetic as-of boundary tests; no provider calls or executable quotes. */
class CanonicalResearchDecisionBoundaryTest {
    private static final Instant AVAILABLE = Instant.parse("2026-11-13T04:00:00Z");
    private static final Instant ACQUIRED = AVAILABLE.plusNanos(1);

    private static CanonicalResearchEvidenceGate.Evidence evidence(
            String provider, Instant decisionAt, boolean rightsVerified) {
        return new CanonicalResearchEvidenceGate.Evidence(
                provider, "NSE:LISTING:42", "ONE_MINUTE", "dataset-v2",
                ACQUIRED, AVAILABLE, decisionAt, true, true, true, rightsVerified);
    }

    @Test
    void acquiredAtBoundaryMustBeReachedEvenWhenBarWasAvailableEarlier() {
        for (String provider : new String[] {"ANGEL_ONE", "UPSTOX_ANALYTICS"}) {
            var beforeAcquisition = CanonicalResearchEvidenceGate.evaluate(
                    evidence(provider, AVAILABLE, true));
            assertThat(beforeAcquisition.researchEligible()).isFalse();
            assertThat(beforeAcquisition.reason()).isEqualTo("lookahead");
            assertThat(beforeAcquisition.paperExecutionAuthorized()).isFalse();

            var atAcquisition = CanonicalResearchEvidenceGate.evaluate(
                    evidence(provider, ACQUIRED, true));
            assertThat(atAcquisition.researchEligible()).isTrue();
            assertThat(atAcquisition.reason()).isEqualTo("research_only");
            assertThat(atAcquisition.paperExecutionAuthorized()).isFalse();
        }
    }

    @Test
    void laterDecisionCannotOverrideMissingUsageRights() {
        for (String provider : new String[] {"ANGEL_ONE", "UPSTOX_ANALYTICS"}) {
            var decision = CanonicalResearchEvidenceGate.evaluate(
                    evidence(provider, ACQUIRED.plusSeconds(86400), false));
            assertThat(decision.researchEligible()).isFalse();
            assertThat(decision.reason()).isEqualTo("usage_rights_unverified");
            assertThat(decision.paperExecutionAuthorized()).isFalse();
        }
    }
}
