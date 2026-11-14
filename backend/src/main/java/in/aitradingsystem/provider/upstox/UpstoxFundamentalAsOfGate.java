package in.aitradingsystem.provider.upstox;

import java.time.Instant;
import java.util.Set;

/**
 * Fail-closed eligibility for externally licensed fundamental/event observations.
 * Evaluates metadata only: no provider calls, values, storage, or rights discovery.
 */
public final class UpstoxFundamentalAsOfGate {
    private UpstoxFundamentalAsOfGate() {}

    public enum Purpose { READ, RETAIN, TRAIN, PUBLISH }
    public enum Decision { ALLOW, RIGHTS_UNVERIFIED, RIGHTS_EXPIRED, MISSING_PROVENANCE,
        MISSING_AVAILABILITY, LOOKAHEAD, UNRESOLVED_LISTING }

    public record Evidence(String sourceReference, String entitlementProofReference,
                           Instant validFrom, Instant validUntil, Set<Purpose> allowedPurposes,
                           String canonicalInstrumentId, String listingEpisodeId,
                           Instant publishedAt, Instant availableAt) {
        public Evidence {
            allowedPurposes = allowedPurposes == null ? Set.of() : Set.copyOf(allowedPurposes);
        }
    }

    public static Decision assess(Evidence evidence, Purpose purpose, Instant asOf) {
        if (evidence == null || purpose == null || asOf == null) return Decision.RIGHTS_UNVERIFIED;
        if (blank(evidence.entitlementProofReference()) || evidence.allowedPurposes().isEmpty()
                || !evidence.allowedPurposes().contains(purpose)) return Decision.RIGHTS_UNVERIFIED;
        if (evidence.validFrom() == null || evidence.validUntil() == null
                || !evidence.validFrom().isBefore(evidence.validUntil())
                || asOf.isBefore(evidence.validFrom()) || !asOf.isBefore(evidence.validUntil()))
            return Decision.RIGHTS_EXPIRED;
        if (blank(evidence.sourceReference())) return Decision.MISSING_PROVENANCE;
        if (blank(evidence.canonicalInstrumentId()) || blank(evidence.listingEpisodeId()))
            return Decision.UNRESOLVED_LISTING;
        if (evidence.publishedAt() == null || evidence.availableAt() == null)
            return Decision.MISSING_AVAILABILITY;
        if (evidence.availableAt().isBefore(evidence.publishedAt())
                || evidence.publishedAt().isAfter(asOf) || evidence.availableAt().isAfter(asOf))
            return Decision.LOOKAHEAD;
        return Decision.ALLOW;
    }

    private static boolean blank(String value) { return value == null || value.isBlank(); }
}
