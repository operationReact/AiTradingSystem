package in.aitradingsystem.research;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import org.junit.jupiter.api.Test;

/** Synthetic as-of boundary matrix: no provider calls, executable quotes, or orders. */
class CanonicalResearchAsOfBoundaryMatrixTest {
    private static final Instant DECISION = Instant.parse("2026-11-13T09:15:00Z");

    private static CanonicalResearchEvidenceGate.Evidence evidence(
            String provider, Instant acquired, Instant available) {
        return new CanonicalResearchEvidenceGate.Evidence(
                provider, "NSE:LISTING:42", "ONE_MINUTE", "dataset-v2",
                acquired, available, DECISION, true, true, true, true);
    }

    private static void assertOutcome(
            String provider, Instant acquired, Instant available,
            boolean eligible, String reason) {
        var result = CanonicalResearchEvidenceGate.evaluate(
                evidence(provider, acquired, available));
        assertThat(result.researchEligible()).isEqualTo(eligible);
        assertThat(result.reason()).isEqualTo(reason);
        assertThat(result.paperExecutionAuthorized()).isFalse();
    }

    @Test
    void exactDecisionBoundaryIsAllowedButOneNanosecondOfLookaheadIsRejected() {
        for (String provider : new String[] {"ANGEL_ONE", "UPSTOX_ANALYTICS"}) {
            Instant before = DECISION.minusNanos(1);
            Instant after = DECISION.plusNanos(1);
            assertOutcome(provider, DECISION, DECISION, true, "research_only");
            assertOutcome(provider, before, DECISION, true, "research_only");
            assertOutcome(provider, DECISION, before, true, "research_only");
            assertOutcome(provider, after, DECISION, false, "lookahead");
            assertOutcome(provider, DECISION, after, false, "lookahead");
            assertOutcome(provider, after, after, false, "lookahead");
        }
    }

    @Test
    void independentAcquisitionAndAvailabilityTimestampsBothGateResearch() {
        for (String provider : new String[] {"ANGEL_ONE", "UPSTOX_ANALYTICS"}) {
            Instant historical = DECISION.minusSeconds(86400);
            Instant future = DECISION.plusSeconds(60);
            assertOutcome(provider, historical, DECISION, true, "research_only");
            assertOutcome(provider, DECISION, historical, true, "research_only");
            assertOutcome(provider, historical, future, false, "lookahead");
            assertOutcome(provider, future, historical, false, "lookahead");
        }
    }
}
