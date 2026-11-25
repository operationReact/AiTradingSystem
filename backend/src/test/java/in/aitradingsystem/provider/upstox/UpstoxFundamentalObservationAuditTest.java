package in.aitradingsystem.provider.upstox;

import static org.junit.jupiter.api.Assertions.*;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class UpstoxFundamentalObservationAuditTest {
    private static final Instant T = Instant.parse("2026-11-13T10:00:00Z");

    @Test void missingIsNotZero() {
        assertEquals(UpstoxFundamentalObservationAudit.Result.UNOBSERVED,
                UpstoxFundamentalObservationAudit.evaluate(
                        new UpstoxFundamentalObservationAudit.Observation(null, null, T, 60, null, false)));
        assertEquals(UpstoxFundamentalObservationAudit.Result.RECENT_OBSERVATION,
                UpstoxFundamentalObservationAudit.evaluate(
                        new UpstoxFundamentalObservationAudit.Observation(T, T, T, 60, 0, true)));
    }

    @Test void futureAndReversedObservationsFailClosed() {
        assertEquals(UpstoxFundamentalObservationAudit.Result.FUTURE_EVIDENCE,
                UpstoxFundamentalObservationAudit.evaluate(
                        new UpstoxFundamentalObservationAudit.Observation(T, T.plusNanos(1), T, 60, 1, true)));
        assertEquals(UpstoxFundamentalObservationAudit.Result.INVALID_SEQUENCE,
                UpstoxFundamentalObservationAudit.evaluate(
                        new UpstoxFundamentalObservationAudit.Observation(T, T.minusNanos(1), T, 60, 1, true)));
    }

    @Test void exactFreshnessBoundaryIsInclusive() {
        assertEquals(UpstoxFundamentalObservationAudit.Result.RECENT_OBSERVATION,
                UpstoxFundamentalObservationAudit.evaluate(
                        new UpstoxFundamentalObservationAudit.Observation(T.minusSeconds(60), T.minusSeconds(60), T, 60, 1, true)));
        assertEquals(UpstoxFundamentalObservationAudit.Result.STALE,
                UpstoxFundamentalObservationAudit.evaluate(
                        new UpstoxFundamentalObservationAudit.Observation(T.minusSeconds(61), T.minusSeconds(61), T, 60, 1, true)));
    }

    @Test void rejectedResponsesCannotClaimRecordCounts() {
        assertThrows(IllegalArgumentException.class, () ->
                new UpstoxFundamentalObservationAudit.Observation(T, T, T, 60, 1, false));
        assertThrows(IllegalArgumentException.class, () ->
                new UpstoxFundamentalObservationAudit.Observation(T, T, T, 60, null, true));
        assertThrows(IllegalArgumentException.class, () ->
                new UpstoxFundamentalObservationAudit.Observation(T, T, T, 86401, null, false));
    }
}
