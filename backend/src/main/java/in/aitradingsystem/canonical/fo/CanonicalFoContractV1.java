package in.aitradingsystem.canonical.fo;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Objects;

/**
 * Provider-neutral immutable NSE F&O metadata contract v1.
 * Validation is structural only: it does not assert that an exchange listed a contract.
 * Consumers must additionally verify a dated exchange listing receipt and source-use grant.
 */
public record CanonicalFoContractV1(
        String instrumentKey, String underlyingListingId, String exchange,
        LocalDate expiry, BigDecimal strike, OptionSide side,
        int lotSize, BigDecimal tickSize, String source,
        Instant observedAt, Instant acquiredAt, Instant availableAt,
        boolean listingVerified, boolean ephemeralReadPermitted,
        boolean displayPermitted, boolean retentionPermitted, boolean trainingPermitted) {

    public enum OptionSide { CE, PE }

    public static final String VERSION = "minttick.canonical.fo-contract.v1";

    public CanonicalFoContractV1 {
        require(instrumentKey, "instrumentKey");
        require(underlyingListingId, "underlyingListingId");
        require(source, "source");
        if (!"NSE_FO".equals(exchange)) throw new IllegalArgumentException("exchange must be NSE_FO");
        if (!instrumentKey.startsWith("NSE_FO|") || instrumentKey.length() <= "NSE_FO|".length())
            throw new IllegalArgumentException("invalid NSE_FO instrumentKey");
        Objects.requireNonNull(expiry, "expiry");
        Objects.requireNonNull(side, "side");
        if (strike == null || strike.signum() <= 0) throw new IllegalArgumentException("positive strike required");
        if (lotSize <= 0) throw new IllegalArgumentException("positive lotSize required");
        if (tickSize == null || tickSize.signum() <= 0) throw new IllegalArgumentException("positive tickSize required");
        Objects.requireNonNull(acquiredAt, "acquiredAt");
        Objects.requireNonNull(availableAt, "availableAt");
        if (availableAt.isBefore(acquiredAt)) throw new IllegalArgumentException("availableAt precedes acquiredAt");
        if (observedAt != null && observedAt.isAfter(acquiredAt))
            throw new IllegalArgumentException("observedAt after acquiredAt");
    }

    private static void require(String value, String field) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " required");
    }

    /** Fails closed on missing exchange-listing evidence, rights or lookahead. */
    public boolean eligibleForPrivatePaperResearch(Instant decisionAt, LocalDate sessionDate) {
        return listingVerified && ephemeralReadPermitted && displayPermitted
                && decisionAt != null && sessionDate != null
                && !availableAt.isAfter(decisionAt) && !expiry.isBefore(sessionDate);
    }

    /** Historical storage and model training are independent explicit grants. */
    public boolean eligibleForTraining(Instant decisionAt, LocalDate sessionDate) {
        return eligibleForPrivatePaperResearch(decisionAt, sessionDate)
                && retentionPermitted && trainingPermitted;
    }
}
