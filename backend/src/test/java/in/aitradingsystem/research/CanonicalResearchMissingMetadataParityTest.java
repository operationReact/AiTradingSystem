package in.aitradingsystem.research;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import org.junit.jupiter.api.Test;

/** Provider-neutral synthetic contract tests. Never authorize orders or use live data. */
class CanonicalResearchMissingMetadataParityTest {
    private static final Instant AT = Instant.parse("2026-11-13T04:00:00Z");

    private static CanonicalResearchEvidenceGate.Evidence evidence(
            String provider, String listing, String interval, String version,
            Instant acquired, Instant available, Instant decision) {
        return new CanonicalResearchEvidenceGate.Evidence(
                provider, listing, interval, version, acquired, available, decision,
                true, true, true, true);
    }

    private static void rejected(
            CanonicalResearchEvidenceGate.Evidence evidence, String reason) {
        var decision = CanonicalResearchEvidenceGate.evaluate(evidence);
        assertThat(decision.researchEligible()).isFalse();
        assertThat(decision.reason()).isEqualTo(reason);
        assertThat(decision.paperExecutionAuthorized()).isFalse();
    }

    @Test
    void missingOrWhitespaceLineageFailsClosedForEveryProvider() {
        for (String provider : new String[] {"ANGEL_ONE", "UPSTOX_ANALYTICS"}) {
            rejected(evidence(provider, null, "ONE_MINUTE", "dataset-v2", AT, AT, AT),
                    "missing_lineage");
            rejected(evidence(provider, "  ", "ONE_MINUTE", "dataset-v2", AT, AT, AT),
                    "missing_lineage");
            rejected(evidence(provider, "NSE:LISTING:42", "\t", "dataset-v2", AT, AT, AT),
                    "missing_lineage");
            rejected(evidence(provider, "NSE:LISTING:42", "ONE_MINUTE", "", AT, AT, AT),
                    "missing_lineage");
        }
        rejected(evidence(null, "NSE:LISTING:42", "ONE_MINUTE", "dataset-v2",
                AT, AT, AT), "missing_lineage");
    }

    @Test
    void missingTimeFailsClosedEvenWithVerifiedLineageAndRights() {
        for (String provider : new String[] {"ANGEL_ONE", "UPSTOX_ANALYTICS"}) {
            rejected(evidence(provider, "NSE:LISTING:42", "ONE_MINUTE", "dataset-v2",
                    null, AT, AT), "missing_time");
            rejected(evidence(provider, "NSE:LISTING:42", "ONE_MINUTE", "dataset-v2",
                    AT, null, AT), "missing_time");
            rejected(evidence(provider, "NSE:LISTING:42", "ONE_MINUTE", "dataset-v2",
                    AT, AT, null), "missing_time");
        }
        rejected(null, "missing_evidence");
    }
}
