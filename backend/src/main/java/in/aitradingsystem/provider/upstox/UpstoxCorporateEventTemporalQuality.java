package in.aitradingsystem.provider.upstox;

import java.time.Instant;

/**
 * Read-only quality classification for externally sourced corporate-event metadata.
 * This does not grant provider access, retention, adjustment, or training rights.
 */
public final class UpstoxCorporateEventTemporalQuality {
    private UpstoxCorporateEventTemporalQuality() {}

    public enum Status {
        VALID_METADATA, MISSING_IDENTITY, MISSING_SOURCE, MISSING_TIME,
        INCONSISTENT_PUBLICATION, FUTURE_AVAILABLE, INVALID_REVISION
    }

    public record Observation(String canonicalInstrumentId, String listingEpisodeId,
                              String sourceReference, String eventReference,
                              Instant publishedAt, Instant availableAt,
                              Instant observedAt, int revision) {}

    public static Status assess(Observation o, Instant asOf) {
        if (o == null || asOf == null) return Status.MISSING_TIME;
        if (blank(o.canonicalInstrumentId()) || blank(o.listingEpisodeId()))
            return Status.MISSING_IDENTITY;
        if (blank(o.sourceReference()) || blank(o.eventReference()))
            return Status.MISSING_SOURCE;
        if (o.publishedAt() == null || o.availableAt() == null || o.observedAt() == null)
            return Status.MISSING_TIME;
        if (o.revision() < 1) return Status.INVALID_REVISION;
        if (o.availableAt().isBefore(o.publishedAt())
                || o.observedAt().isBefore(o.availableAt()))
            return Status.INCONSISTENT_PUBLICATION;
        if (o.availableAt().isAfter(asOf) || o.observedAt().isAfter(asOf)) return Status.FUTURE_AVAILABLE;
        return Status.VALID_METADATA;
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }
}
