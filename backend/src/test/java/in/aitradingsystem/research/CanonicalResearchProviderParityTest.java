package in.aitradingsystem.research;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import org.junit.jupiter.api.Test;

/** Synthetic provider-neutral contract checks; no broker requests or order execution. */
class CanonicalResearchProviderParityTest {
    private static final Instant DECISION = Instant.parse("2026-11-13T04:00:00Z");

    private static CanonicalResearchEvidenceGate.Evidence evidence(
            String provider, Instant acquired, Instant available) {
        return new CanonicalResearchEvidenceGate.Evidence(
                provider, "NSE:LISTING:42", "ONE_MINUTE", "dataset-v2",
                acquired, available, DECISION, true, true, true, true);
    }

    @Test
    void equivalentEvidenceHasIdenticalResearchDecisionAcrossSources() {
        var acquired = DECISION.minusSeconds(1);
        var available = DECISION.minusSeconds(2);
        var angel = CanonicalResearchEvidenceGate.evaluate(
                evidence("ANGEL_ONE", acquired, available));
        var upstox = CanonicalResearchEvidenceGate.evaluate(
                evidence("UPSTOX_ANALYTICS", acquired, available));

        assertThat(angel).isEqualTo(upstox);
        assertThat(angel.researchEligible()).isTrue();
        assertThat(angel.paperExecutionAuthorized()).isFalse();
    }

    @Test
    void identicalLookaheadIsRejectedRegardlessOfProvider() {
        for (String provider : new String[] {"ANGEL_ONE", "UPSTOX_ANALYTICS"}) {
            var result = CanonicalResearchEvidenceGate.evaluate(
                    evidence(provider, DECISION.plusNanos(1), DECISION.minusSeconds(1)));
            assertThat(result.researchEligible()).isFalse();
            assertThat(result.reason()).isEqualTo("lookahead");
            assertThat(result.paperExecutionAuthorized()).isFalse();
        }
    }

    @Test
    void whitespaceOnlyProviderIsMissingLineage() {
        var result = CanonicalResearchEvidenceGate.evaluate(
                evidence("  ", DECISION.minusSeconds(1), DECISION.minusSeconds(2)));
        assertThat(result.researchEligible()).isFalse();
        assertThat(result.reason()).isEqualTo("missing_lineage");
        assertThat(result.paperExecutionAuthorized()).isFalse();
    }

    @Test
    void unknownProviderIsNotAnExecutionCredential() {
        var result = CanonicalResearchEvidenceGate.evaluate(
                evidence("FUTURE_SOURCE", DECISION.minusSeconds(1), DECISION.minusSeconds(2)));
        assertThat(result.researchEligible()).isTrue();
        assertThat(result.paperExecutionAuthorized()).isFalse();
    }
}
