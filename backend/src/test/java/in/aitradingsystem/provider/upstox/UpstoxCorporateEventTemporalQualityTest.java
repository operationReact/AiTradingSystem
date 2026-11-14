package in.aitradingsystem.provider.upstox;

import static in.aitradingsystem.provider.upstox.UpstoxCorporateEventTemporalQuality.*;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.Instant;
import org.junit.jupiter.api.Test;

class UpstoxCorporateEventTemporalQualityTest {
    private static final Instant AS_OF = Instant.parse("2026-11-14T09:00:00Z");
    private static final Instant PUBLISHED = AS_OF.minusSeconds(3600);
    private static final Instant AVAILABLE = AS_OF.minusSeconds(1800);
    private static Observation observation(Instant published, Instant available,
                                           Instant observed, int revision) {
        return new Observation("canonical", "listing-episode", "source", "event",
                published, available, observed, revision);
    }

    @Test void acceptsConsistentMetadataWithoutClaimingRights() {
        assertEquals(Status.VALID_METADATA,
                assess(observation(PUBLISHED, AVAILABLE, AS_OF, 1), AS_OF));
    }

    @Test void deniesLookaheadAndBackdatedAvailability() {
        assertEquals(Status.FUTURE_AVAILABLE,
                assess(observation(PUBLISHED, AS_OF.plusSeconds(1), AS_OF.plusSeconds(2), 1), AS_OF));
        assertEquals(Status.INCONSISTENT_PUBLICATION,
                assess(observation(AVAILABLE, PUBLISHED, AS_OF, 1), AS_OF));
        assertEquals(Status.INCONSISTENT_PUBLICATION,
                assess(observation(PUBLISHED, AVAILABLE, PUBLISHED, 1), AS_OF));
    }

    @Test void rejectsLateObservedRevisionsEvenWithHistoricalPublication() {
        assertEquals(Status.FUTURE_AVAILABLE,
                assess(observation(PUBLISHED, AVAILABLE, AS_OF.plusSeconds(1), 2), AS_OF));
        assertEquals(Status.VALID_METADATA,
                assess(observation(PUBLISHED, AVAILABLE, AS_OF, 2), AS_OF));
    }

    @Test void rejectsMissingTimestampsAndInvalidRevision() {
        assertEquals(Status.MISSING_TIME, assess(observation(null, AVAILABLE, AS_OF, 1), AS_OF));
        assertEquals(Status.MISSING_TIME, assess(observation(PUBLISHED, AVAILABLE, null, 1), AS_OF));
        assertEquals(Status.INVALID_REVISION,
                assess(observation(PUBLISHED, AVAILABLE, AS_OF, 0), AS_OF));
    }

    @Test void rejectsUnresolvedListingAndMissingProvenance() {
        var missingListing = new Observation("canonical", "", "source", "event",
                PUBLISHED, AVAILABLE, AS_OF, 1);
        assertEquals(Status.MISSING_IDENTITY, assess(missingListing, AS_OF));
        var missingSource = new Observation("canonical", "episode", " ", "event",
                PUBLISHED, AVAILABLE, AS_OF, 1);
        assertEquals(Status.MISSING_SOURCE, assess(missingSource, AS_OF));
    }
}
