package in.aitradingsystem.provider.upstox;

import static org.junit.jupiter.api.Assertions.*;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class UpstoxFundamentalSourceAvailabilityTest {
    private static final Instant T = Instant.parse("2026-11-13T10:00:00Z");

    @Test void unknownEndpointIsNotZeroCoverage() {
        var evidence = new UpstoxFundamentalSourceAvailability.Evidence(
            UpstoxFundamentalSourceAvailability.Domain.ISSUER_FILING,
            UpstoxFundamentalSourceAvailability.Observation.ENDPOINT_UNCONFIRMED,
            UpstoxFundamentalSourceAvailability.Permission.UNKNOWN, null, null, null);
        assertNull(evidence.observedRecords());
        assertEquals(UpstoxFundamentalSourceAvailability.Readiness.NO_EVIDENCE,
            UpstoxFundamentalSourceAvailability.readOnlyReadiness(evidence));
    }

    @Test void observedZeroDiffersFromMissingAndStillGrantsNoRights() {
        var evidence = new UpstoxFundamentalSourceAvailability.Evidence(
            UpstoxFundamentalSourceAvailability.Domain.CURRENT_KEY_RATIO,
            UpstoxFundamentalSourceAvailability.Observation.AUTHORIZED_EMPTY,
            UpstoxFundamentalSourceAvailability.Permission.READ_ONLY_CONFIRMED, T, 0, "synthetic");
        assertEquals(0, evidence.observedRecords());
        assertEquals(UpstoxFundamentalSourceAvailability.Readiness.READ_OBSERVED_RIGHTS_BLOCKED,
            UpstoxFundamentalSourceAvailability.readOnlyReadiness(evidence));
    }

    @Test void failuresCannotBeMistakenForObservedCounts() {
        assertThrows(IllegalArgumentException.class, () -> new UpstoxFundamentalSourceAvailability.Evidence(
            UpstoxFundamentalSourceAvailability.Domain.HISTORICAL_KEY_RATIO,
            UpstoxFundamentalSourceAvailability.Observation.RATE_LIMITED,
            UpstoxFundamentalSourceAvailability.Permission.UNKNOWN, T, 0, "synthetic"));
        assertThrows(IllegalArgumentException.class, () -> new UpstoxFundamentalSourceAvailability.Evidence(
            UpstoxFundamentalSourceAvailability.Domain.CORPORATE_ACTION,
            UpstoxFundamentalSourceAvailability.Observation.AUTHORIZED_NONZERO,
            UpstoxFundamentalSourceAvailability.Permission.UNKNOWN, T, null, "synthetic"));
    }

    @Test void positiveReadDoesNotImplyTrainingOrRetention() {
        var evidence = new UpstoxFundamentalSourceAvailability.Evidence(
            UpstoxFundamentalSourceAvailability.Domain.CURRENT_KEY_RATIO,
            UpstoxFundamentalSourceAvailability.Observation.AUTHORIZED_NONZERO,
            UpstoxFundamentalSourceAvailability.Permission.UNKNOWN, T, 2, "synthetic");
        assertEquals(UpstoxFundamentalSourceAvailability.Readiness.READ_OBSERVED_RIGHTS_BLOCKED,
            UpstoxFundamentalSourceAvailability.readOnlyReadiness(evidence));
    }
}
