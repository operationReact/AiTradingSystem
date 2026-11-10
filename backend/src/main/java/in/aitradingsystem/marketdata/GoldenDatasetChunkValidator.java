package in.aitradingsystem.marketdata;

import java.time.Instant;

/**
 * Independent source-domain quality gate: inspect raw bytes, exact session
 * calendar/listing episode, timestamp availability, revisions, duplicates
 * and required domain fields. A provider's claimed HTTP count is NOT proof.
 *
 * No production validator is registered by D20-03B. A reviewed adapter and
 * validator are prerequisites for making real fetches.
 */
public interface GoldenDatasetChunkValidator {
    ValidatedChunk validate(GoldenHistoricalDatasetProvider.ChunkRequest request,
                            GoldenHistoricalDatasetProvider.SourcePayload source);

    record ValidatedChunk(long expectedRecords, long validRecords,
                          Instant firstAvailableAt, Instant lastAvailableAt) {}
}
