package in.aitradingsystem.canonical.fo;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;

/**
 * Read-only canonical evidence boundary; never an order authorization.
 * A chain capability receipt is not a live per-contract quote. Unknown evidence
 * remains UNKNOWN rather than being represented as zero coverage or fresh ticks.
 */
public final class CanonicalFoQuoteEvidenceV1 {
    public static final String VERSION = "minttick.canonical.fo-quote-evidence.v1";
    private CanonicalFoQuoteEvidenceV1() {}

    public enum State { UNKNOWN, RIGHTS_DENIED, METADATA_UNVERIFIED, TICK_NOT_OBSERVED, STALE, QUOTE_OBSERVED }

    public record Evidence(CanonicalFoContractV1 contract, Instant exchangeTickAt,
                           Instant acquiredAt, Instant availableAt, boolean rightsVerified,
                           boolean perContractV3TickVerified) {}

    /**
     * Quote evidence is observational only. It does not establish broker permission,
     * trading-session validity, bid/ask executability, or PAPER order acceptance.
     */
    public static State assess(Evidence e, Instant asOf, LocalDate session, Duration maxAge) {
        if (e == null || e.contract() == null || asOf == null || session == null
                || maxAge == null || maxAge.isNegative() || maxAge.isZero()) return State.UNKNOWN;
        if (!e.rightsVerified() || !e.contract().ephemeralReadPermitted()
                || !e.contract().displayPermitted()) return State.RIGHTS_DENIED;
        if (!e.contract().listingVerified() || e.contract().expiry().isBefore(session)
                || e.contract().availableAt().isAfter(asOf)) return State.METADATA_UNVERIFIED;
        if (!e.perContractV3TickVerified() || e.exchangeTickAt() == null
                || e.acquiredAt() == null || e.availableAt() == null)
            return State.TICK_NOT_OBSERVED;
        if (e.exchangeTickAt().isAfter(e.acquiredAt())
                || e.acquiredAt().isAfter(e.availableAt())
                || e.availableAt().isAfter(asOf)
                || e.exchangeTickAt().isAfter(asOf)) return State.UNKNOWN;
        if (Duration.between(e.exchangeTickAt(), asOf).compareTo(maxAge) > 0)
            return State.STALE;
        return State.QUOTE_OBSERVED;
    }
}
