package in.aitradingsystem.canonical.fo;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

/**
 * Boundary between a provider option-chain observation and canonical research metadata.
 * Observing a contract is not evidence of exchange listing, lot/tick economics or use rights.
 * This mapper never fills missing provider values with NSE defaults.
 */
public final class CanonicalFoObservedMetadataGateV1 {
    public static final String VERSION = "minttick.canonical.fo-observed-metadata-gate.v1";

    private CanonicalFoObservedMetadataGateV1() {}

    public record Observation(String instrumentKey, String underlyingListingId,
            String exchange, LocalDate expiry, BigDecimal strike,
            CanonicalFoContractV1.OptionSide side, Integer lotSize, BigDecimal tickSize,
            String source, Instant observedAt, Instant acquiredAt, Instant availableAt,
            boolean listingVerified, boolean ephemeralReadPermitted, boolean displayPermitted,
            boolean retentionPermitted, boolean trainingPermitted) {}

    /**
     * A missing lot/tick or unverified rights cannot be mapped into eligible metadata.
     * Mapping a structurally complete observation does not grant order permission.
     */
    public static CanonicalFoContractV1 map(Observation o) {
        if (o == null || o.lotSize() == null || o.tickSize() == null)
            throw new IllegalArgumentException("provider lotSize and tickSize evidence required");
        return new CanonicalFoContractV1(o.instrumentKey(), o.underlyingListingId(),
                o.exchange(), o.expiry(), o.strike(), o.side(), o.lotSize(), o.tickSize(),
                o.source(), o.observedAt(), o.acquiredAt(), o.availableAt(),
                o.listingVerified(), o.ephemeralReadPermitted(), o.displayPermitted(),
                o.retentionPermitted(), o.trainingPermitted());
    }

    /** No implicit permission from successful provider authentication or source observation. */
    public static boolean permittedPrivateResearch(Observation o, Instant decisionAt, LocalDate sessionDate) {
        if (o == null || !o.listingVerified() || !o.ephemeralReadPermitted() || !o.displayPermitted())
            return false;
        try {
            return map(o).eligibleForPrivatePaperResearch(decisionAt, sessionDate);
        } catch (IllegalArgumentException | NullPointerException e) {
            return false;
        }
    }
}
