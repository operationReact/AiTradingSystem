package in.aitradingsystem.canonical.fo;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Objects;

/**
 * Broker-neutral, read-only producer-to-consumer metadata handshake.
 * The provider metadata must be independently sourced; this class never
 * infers exchange listing, source rights, or executable quote freshness.
 */
public record CanonicalFoIdentityHandoffV1(
        String instrumentKey, String underlyingListingId, String exchange,
        LocalDate expiry, BigDecimal strike, CanonicalFoContractV1.OptionSide side,
        int lotSize, BigDecimal tickSize, Instant availableAt) {

    public static final String VERSION = "minttick.canonical.fo-identity-handoff.v1";

    public CanonicalFoIdentityHandoffV1 {
        if (instrumentKey == null || !instrumentKey.startsWith("NSE_FO|")
                || instrumentKey.length() <= "NSE_FO|".length())
            throw new IllegalArgumentException("NSE_FO instrumentKey required");
        if (underlyingListingId == null || underlyingListingId.isBlank())
            throw new IllegalArgumentException("underlying listing identity required");
        if (!"NSE_FO".equals(exchange)) throw new IllegalArgumentException("NSE_FO exchange required");
        Objects.requireNonNull(expiry, "expiry");
        Objects.requireNonNull(side, "side");
        if (strike == null || strike.signum() <= 0)
            throw new IllegalArgumentException("positive strike required");
        if (lotSize <= 0) throw new IllegalArgumentException("positive lotSize required");
        if (tickSize == null || tickSize.signum() <= 0)
            throw new IllegalArgumentException("positive tickSize required");
        Objects.requireNonNull(availableAt, "availableAt");
    }

    /** All identity and contract economics must agree, without silent coercion. */
    public boolean matches(CanonicalFoContractV1 producer) {
        return producer != null
                && instrumentKey.equals(producer.instrumentKey())
                && underlyingListingId.equals(producer.underlyingListingId())
                && exchange.equals(producer.exchange())
                && expiry.equals(producer.expiry())
                && strike.compareTo(producer.strike()) == 0
                && side == producer.side()
                && lotSize == producer.lotSize()
                && tickSize.compareTo(producer.tickSize()) == 0
                && availableAt.equals(producer.availableAt());
    }

    /**
     * Research metadata only, never order permission. Requires a separately
     * verified listing and explicit private read/display rights on producer.
     * A matching contract is NOT evidence of an executable V3 quote.
     */
    public boolean eligibleForPrivatePaperResearch(
            CanonicalFoContractV1 producer, Instant decisionAt, LocalDate sessionDate) {
        return matches(producer)
                && producer.eligibleForPrivatePaperResearch(decisionAt, sessionDate);
    }
}
