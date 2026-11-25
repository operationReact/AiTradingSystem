package in.aitradingsystem.canonical;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class CanonicalCoverageManifestTest {
    private static final CanonicalCoverageManifest.Partition PARTITION =
            new CanonicalCoverageManifest.Partition("listing-1", "NSE", "episode-1",
                    2025, "ONE_DAY", "ANGEL_ONE", "dataset-v1");
    private static final Instant NOW = Instant.parse("2026-11-13T12:00:00Z");
    private static final Instant AVAILABLE = Instant.parse("2026-11-12T12:00:00Z");

    @Test void validatedRowsRequirePositiveCountChecksumAndVerifiedRights() {
        assertThrows(IllegalArgumentException.class, () -> evidence(
                CanonicalCoverageManifest.Status.VALIDATED, 0, true, "checksum"));
        assertThrows(IllegalArgumentException.class, () -> evidence(
                CanonicalCoverageManifest.Status.VALIDATED, 1, false, "checksum"));
        assertThrows(IllegalArgumentException.class, () -> evidence(
                CanonicalCoverageManifest.Status.VALIDATED, 1, true, ""));
    }

    @Test void asOfAndStatusMustFailClosed() {
        var verified = evidence(CanonicalCoverageManifest.Status.VALIDATED, 5, true, "sha256:test");
        assertTrue(verified.researchInputEligible(NOW));
        assertFalse(verified.researchInputEligible(AVAILABLE.minusSeconds(1)));
        assertFalse(evidence(CanonicalCoverageManifest.Status.ACQUIRED, 5, false, "")
                .researchInputEligible(NOW));
        assertFalse(evidence(CanonicalCoverageManifest.Status.STALE, 5, true, "")
                .researchInputEligible(NOW));
    }

    @Test void summaryReportsEvidenceNotFabricatedProviderTotals() {
        var rows = List.of(evidence(CanonicalCoverageManifest.Status.ACQUIRED, 5, false, ""),
                evidence(CanonicalCoverageManifest.Status.GAP, 0, false, ""),
                evidence(CanonicalCoverageManifest.Status.DENIED, 0, false, ""));
        var summary = CanonicalCoverageManifest.summarize(rows);
        assertEquals(1, summary.acquired());
        assertEquals(0, summary.validated());
        assertEquals(1, summary.gap());
        assertEquals(1, summary.denied());
        assertEquals(3, summary.total());
    }

    @Test void rejectedFutureAvailabilityAndContradictoryDenial() {
        assertThrows(IllegalArgumentException.class, () -> new CanonicalCoverageManifest.Evidence(
                PARTITION, CanonicalCoverageManifest.Status.ACQUIRED, 1, NOW,
                NOW.plusSeconds(1), "", "fixture", false));
        assertThrows(IllegalArgumentException.class, () -> evidence(
                CanonicalCoverageManifest.Status.DENIED, 0, true, ""));
    }

    private static CanonicalCoverageManifest.Evidence evidence(
            CanonicalCoverageManifest.Status status, long rows, boolean grant, String checksum) {
        return new CanonicalCoverageManifest.Evidence(
                PARTITION, status, rows, NOW, AVAILABLE, checksum, "synthetic-fixture", grant);
    }
}
