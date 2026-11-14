package in.aitradingsystem.provider.upstox;

import static org.junit.jupiter.api.Assertions.*;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class UpstoxFundamentalSourceStatusTest {
    private static final Instant T = Instant.parse("2026-11-14T03:00:00Z");
    private UpstoxFundamentalSourceAvailability.Evidence source(
            UpstoxFundamentalSourceAvailability.Observation observation, Integer count) {
        return new UpstoxFundamentalSourceAvailability.Evidence(
                UpstoxFundamentalSourceAvailability.Domain.CURRENT_KEY_RATIO,
                observation, UpstoxFundamentalSourceAvailability.Permission.UNKNOWN,
                observation == UpstoxFundamentalSourceAvailability.Observation.NOT_PROBED ? null : T,
                count, observation == UpstoxFundamentalSourceAvailability.Observation.NOT_PROBED ? null : "test-receipt");
    }
    private UpstoxFundamentalObservationAudit.Observation receipt(Integer count, Instant responded) {
        return new UpstoxFundamentalObservationAudit.Observation(T, responded, T.plusSeconds(60),
                120, count, true);
    }
    @Test void unobservedNeverMeansZero() {
        var status = UpstoxFundamentalSourceStatus.assess(
                source(UpstoxFundamentalSourceAvailability.Observation.NOT_PROBED, null), null);
        assertEquals(UpstoxFundamentalSourceStatus.State.NOT_OBSERVED, status.state());
        assertNull(status.observedRecords());
        assertFalse(status.researchPermitted());
    }
    @Test void acceptedEmptyIsDistinctFromMissing() {
        var status = UpstoxFundamentalSourceStatus.assess(
                source(UpstoxFundamentalSourceAvailability.Observation.AUTHORIZED_EMPTY, 0), receipt(0, T));
        assertEquals(UpstoxFundamentalSourceStatus.State.VERIFIED_EMPTY_READ_ONLY, status.state());
        assertEquals(0, status.observedRecords());
        assertFalse(status.researchPermitted());
    }
    @Test void acceptedNonzeroStillCannotAuthorizeResearch() {
        var status = UpstoxFundamentalSourceStatus.assess(
                source(UpstoxFundamentalSourceAvailability.Observation.AUTHORIZED_NONZERO, 3), receipt(3, T));
        assertEquals(UpstoxFundamentalSourceStatus.State.VERIFIED_NONZERO_READ_ONLY, status.state());
        assertEquals(3, status.observedRecords());
        assertFalse(status.researchPermitted());
    }
    @Test void mismatchedCountsFailClosed() {
        var status = UpstoxFundamentalSourceStatus.assess(
                source(UpstoxFundamentalSourceAvailability.Observation.AUTHORIZED_NONZERO, 3), receipt(2, T));
        assertEquals(UpstoxFundamentalSourceStatus.State.INVALID_RECEIPT, status.state());
        assertNull(status.observedRecords());
    }
    @Test void staleReceiptDoesNotCountAsFresh() {
        var status = UpstoxFundamentalSourceStatus.assess(
                source(UpstoxFundamentalSourceAvailability.Observation.AUTHORIZED_NONZERO, 3),
                new UpstoxFundamentalObservationAudit.Observation(T, T, T.plusSeconds(200), 60, 3, true));
        assertEquals(UpstoxFundamentalSourceStatus.State.STALE_RECEIPT, status.state());
        assertNull(status.observedRecords());
    }
    @Test void rateLimitDoesNotImplyZero() {
        var status = UpstoxFundamentalSourceStatus.assess(
                source(UpstoxFundamentalSourceAvailability.Observation.RATE_LIMITED, null), null);
        assertEquals(UpstoxFundamentalSourceStatus.State.RATE_LIMITED, status.state());
        assertNull(status.observedRecords());
    }
}
