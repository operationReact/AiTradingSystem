package in.aitradingsystem.marketdata;

import java.time.LocalDate;
import java.util.UUID;

/**
 * Historical listing-episode authority; no broker token joins are permitted.
 * D20-05A leaves operational market_candle and live instruments untouched.
 */
@FunctionalInterface
public interface GoldenHistoricalListingEvidence {
    ListingEpisode resolve(UUID listingId);

    record ListingEpisode(UUID listingId, String exchange,
                          LocalDate listedFrom, LocalDate delistedOn,
                          String identityEvidenceDigest) {}
}
