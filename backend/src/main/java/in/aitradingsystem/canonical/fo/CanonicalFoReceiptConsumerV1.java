package in.aitradingsystem.canonical.fo;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;

/**
 * Read-only boundary for persisted producer receipt metadata. No provider calls, data storage,
 * display authorization, simulated execution, or trading decisions occur here.
 * Missing independent evidence is UNKNOWN or denied, never inferred from chain size.
 */
public final class CanonicalFoReceiptConsumerV1 {
    public static final String VERSION = "minttick.canonical.fo-receipt-consumer.v1";
    private CanonicalFoReceiptConsumerV1() {}

    public record Receipt(String receiptId, String source, String instrumentKey,
            String underlyingListingId, LocalDate expiry, BigDecimal strike,
            CanonicalFoContractV1.OptionSide side, int lotSize, BigDecimal tickSize,
            boolean exchangeListingVerified, boolean ephemeralReadAuthorized,
            boolean privateDisplayAuthorized, boolean simulationAuthorized,
            boolean retentionAuthorized, boolean trainingAuthorized,
            boolean perContractV3TickVerified, Instant exchangeTickAt,
            Instant acquiredAt, Instant availableAt) {}

    public enum Status { UNKNOWN, RIGHTS_DENIED, IDENTITY_MISMATCH, LISTING_UNVERIFIED,
        TICK_NOT_OBSERVED, STALE, QUOTE_OBSERVED }

    public record ReadOnlyResult(String contractVersion, String receiptId, String instrumentKey,
            Status status, boolean privateResearchEligible, boolean simulationEligible,
            boolean retentionEligible, boolean trainingEligible) {}

    public static ReadOnlyResult evaluate(CanonicalFoContractV1 contract, Receipt receipt,
            Instant asOf, LocalDate session, Duration maximumAge) {
        String key = contract == null ? null : contract.instrumentKey();
        String id = receipt == null ? null : receipt.receiptId();
        if (contract == null || receipt == null || blank(id) || asOf == null || session == null
                || maximumAge == null || maximumAge.isZero() || maximumAge.isNegative())
            return result(id, key, Status.UNKNOWN, false, false, false, false);
        if (!contract.instrumentKey().equals(receipt.instrumentKey())
                || !contract.underlyingListingId().equals(receipt.underlyingListingId())
                || !contract.source().equals(receipt.source())
                || !contract.expiry().equals(receipt.expiry())
                || contract.strike().compareTo(receipt.strike() == null ? BigDecimal.ZERO : receipt.strike()) != 0
                || contract.side() != receipt.side() || contract.lotSize() != receipt.lotSize()
                || contract.tickSize().compareTo(receipt.tickSize() == null ? BigDecimal.ZERO : receipt.tickSize()) != 0)
            return result(id, key, Status.IDENTITY_MISMATCH, false, false, false, false);
        if (!receipt.ephemeralReadAuthorized() || !receipt.privateDisplayAuthorized()
                || !contract.ephemeralReadPermitted() || !contract.displayPermitted())
            return result(id, key, Status.RIGHTS_DENIED, false, false, false, false);
        if (!contract.listingVerified() || !receipt.exchangeListingVerified()
                || contract.expiry().isBefore(session) || contract.availableAt().isAfter(asOf))
            return result(id, key, Status.LISTING_UNVERIFIED, false, false, false, false);
        if (!receipt.perContractV3TickVerified() || receipt.exchangeTickAt() == null
                || receipt.acquiredAt() == null || receipt.availableAt() == null)
            return result(id, key, Status.TICK_NOT_OBSERVED, false, false, false, false);
        if (receipt.exchangeTickAt().isAfter(receipt.acquiredAt())
                || receipt.acquiredAt().isAfter(receipt.availableAt())
                || receipt.availableAt().isAfter(asOf) || receipt.exchangeTickAt().isAfter(asOf))
            return result(id, key, Status.UNKNOWN, false, false, false, false);
        if (Duration.between(receipt.exchangeTickAt(), asOf).compareTo(maximumAge) > 0)
            return result(id, key, Status.STALE, false, false, false, false);
        boolean retention = receipt.retentionAuthorized() && contract.retentionPermitted();
        boolean training = retention && receipt.trainingAuthorized() && contract.trainingPermitted();
        return result(id, key, Status.QUOTE_OBSERVED, true,
                receipt.simulationAuthorized(), retention, training);
    }

    private static boolean blank(String s) { return s == null || s.isBlank(); }
    private static ReadOnlyResult result(String id, String key, Status status,
            boolean research, boolean simulation, boolean retention, boolean training) {
        return new ReadOnlyResult(VERSION, id, key, status, research, simulation, retention, training);
    }
}
