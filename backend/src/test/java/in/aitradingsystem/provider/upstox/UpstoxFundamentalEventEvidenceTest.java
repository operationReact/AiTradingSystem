package in.aitradingsystem.provider.upstox;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Instant;
import org.junit.jupiter.api.Test;

class UpstoxFundamentalEventEvidenceTest {
    private static final Instant T = Instant.parse("2026-11-13T10:00:00Z");

    private UpstoxFundamentalEventEvidence.Evidence evidence(
            UpstoxFundamentalEventEvidence.Kind kind,
            UpstoxFundamentalEventEvidence.Rights rights,
            Instant published, Instant available, Instant acquired, boolean verified) {
        return new UpstoxFundamentalEventEvidence.Evidence(kind, "UPSTOX_ANALYTICS",
                "INE002A01018", "synthetic-fixture-only", published, available,
                acquired, rights, verified);
    }

    @Test
    void unknownAndReadOnlyRightsCannotEnterResearch() {
        for (var kind : UpstoxFundamentalEventEvidence.Kind.values()) {
            for (var rights : new UpstoxFundamentalEventEvidence.Rights[] {
                    UpstoxFundamentalEventEvidence.Rights.UNVERIFIED,
                    UpstoxFundamentalEventEvidence.Rights.READ_ONLY }) {
                assertEquals(UpstoxFundamentalEventEvidence.Decision.RIGHTS_BLOCKED,
                        UpstoxFundamentalEventEvidence.researchAt(
                                evidence(kind, rights, T, T, T, true), T));
            }
        }
    }

    @Test
    void missingOrFutureTimestampsFailClosed() {
        var rights = UpstoxFundamentalEventEvidence.Rights.RESEARCH_RETENTION_VERIFIED;
        for (var kind : UpstoxFundamentalEventEvidence.Kind.values()) {
            for (var bad : new Instant[][] {
                    {null, T, T}, {T, null, T}, {T, T, null},
                    {T.plusNanos(1), T.plusNanos(1), T.plusNanos(1)},
                    {T, T.plusNanos(1), T.plusNanos(1)},
                    {T, T, T.plusNanos(1)},
                    {T, T.minusNanos(1), T}}) {
                assertEquals(UpstoxFundamentalEventEvidence.Decision.TIME_BLOCKED,
                        UpstoxFundamentalEventEvidence.researchAt(
                                evidence(kind, rights, bad[0], bad[1], bad[2], true), T));
            }
        }
    }

    @Test
    void exactBoundaryNeedsVerifiedSourceAndExplicitRights() {
        var rights = UpstoxFundamentalEventEvidence.Rights.RESEARCH_RETENTION_VERIFIED;
        assertEquals(UpstoxFundamentalEventEvidence.Decision.SOURCE_BLOCKED,
                UpstoxFundamentalEventEvidence.researchAt(
                        evidence(UpstoxFundamentalEventEvidence.Kind.ISSUER_FILING,
                                rights, T, T, T, false), T));
        assertEquals(UpstoxFundamentalEventEvidence.Decision.ELIGIBLE,
                UpstoxFundamentalEventEvidence.researchAt(
                        evidence(UpstoxFundamentalEventEvidence.Kind.CORPORATE_ACTION,
                                rights, T, T, T, true), T));
        assertThrows(IllegalArgumentException.class, () ->
                new UpstoxFundamentalEventEvidence.Evidence(
                        UpstoxFundamentalEventEvidence.Kind.CURRENT_KEY_RATIO,
                        "UPSTOX_ANALYTICS", "invalid", "fixture", T, T, T, rights, true));
    }
}
