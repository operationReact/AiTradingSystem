package in.aitradingsystem.marketdata;

import static org.junit.jupiter.api.Assertions.*;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class UpstoxCoverageRetryCandidatesTest {
    private static final Instant NOW = Instant.parse("2026-11-13T10:00:00Z");
    private UpstoxCoverageRetryCandidates.Observation observation(String id, Integer expected,
            Integer verified, String reason, Instant attempt, Instant cooldown) {
        return new UpstoxCoverageRetryCandidates.Observation(id, "episode-1", "DAY", 2026, 11,
                NOW.minusSeconds(86400), NOW, "UPSTOX_ANALYTICS", "v1", expected, verified,
                reason, attempt, cooldown);
    }

    @Test void distinguishesUnobservedFromVerifiedZeroAndComplete() {
        var result = UpstoxCoverageRetryCandidates.select(List.of(
                observation("unknown", null, null, "NO_RUN", null, null),
                observation("zero", 4, 0, "GAP", NOW, null),
                observation("complete", 4, 4, "OK", NOW, null)), NOW, 0, 20);
        assertEquals(2, result.total());
        assertEquals("UNKNOWN_COVERAGE", result.items().get(0).disposition());
        assertEquals("REVIEW_RETRY", result.items().get(1).disposition());
    }

    @Test void rightsAndRateLimitAreNotRunnable() {
        var result = UpstoxCoverageRetryCandidates.select(List.of(
                observation("rights", 5, 0, "RIGHTS_UNKNOWN", NOW, null),
                observation("limited", 5, 0, "HTTP_429", NOW, NOW.plusSeconds(60))), NOW, 0, 20);
        assertEquals("COOLDOWN", result.items().get(0).disposition());
        assertEquals("BLOCKED_RIGHTS", result.items().get(1).disposition());
    }

    @Test void futureObservationsExcludedAndPaginationStable() {
        var observations = List.of(observation("c", 1, 0, "GAP", NOW, null),
                observation("a", 1, 0, "GAP", NOW, null),
                observation("b", 1, 0, "GAP", NOW.plusSeconds(1), null));
        var page = UpstoxCoverageRetryCandidates.select(observations, NOW, 0, 1);
        assertEquals(2, page.total());
        assertEquals("a", page.items().get(0).observation().canonicalInstrumentId());
        assertTrue(page.hasMore());
        assertEquals("c", UpstoxCoverageRetryCandidates.select(observations, NOW, 1, 1)
                .items().get(0).observation().canonicalInstrumentId());
    }

    @Test void invalidEvidenceRejected() {
        assertThrows(IllegalArgumentException.class, () -> observation("x", 2, 3, "GAP", NOW, null));
        assertThrows(IllegalArgumentException.class, () ->
                UpstoxCoverageRetryCandidates.select(List.of(), NOW, -1, 1));
    }
    @Test void partiallyFutureWindowDoesNotLeakCoverageCounts() {
        var incomplete = new UpstoxCoverageRetryCandidates.Observation("partial", "episode-1",
                "DAY", 2026, 11, NOW.minusSeconds(60), NOW.plusSeconds(60),
                "UPSTOX_ANALYTICS", "v1", 10, 0, "GAP", NOW, null);
        assertEquals(0, UpstoxCoverageRetryCandidates.select(List.of(incomplete), NOW, 0, 20).total());
    }

    @Test void futureWindowWithoutAttemptDoesNotLeakIntoAsOfRetryQueue() {
        var future = new UpstoxCoverageRetryCandidates.Observation("future", "episode-1",
                "DAY", 2026, 11, NOW.plusSeconds(1), NOW.plusSeconds(3600),
                "UPSTOX_ANALYTICS", "v1", null, null, "NO_RUN", null, null);
        assertEquals(0, UpstoxCoverageRetryCandidates.select(List.of(future), NOW, 0, 20).total());
    }

}
