package in.aitradingsystem.marketdata;

import java.time.LocalDate;
import java.util.UUID;

/**
 * D20-03B ingestion-only SPI. Real adapters must be separately approved for
 * source rights, historical depth, credentials, quotas and allowed frequencies.
 * This interface has no broker order/session capabilities.
 */
public interface GoldenHistoricalDatasetProvider {
    String providerCode();
    String sourceDomain();

    /** One bounded request only; no adapter-side unbounded pagination. */
    SourcePayload fetch(ChunkRequest request);

    record ChunkRequest(
            UUID jobId, UUID chunkId, UUID listingId, String intervalName,
            LocalDate fromDate, LocalDate throughDate, int maxBytes) {}

    record SourcePayload(byte[] bytes, String sourceEvidenceDigest) {
        public SourcePayload {
            if (bytes == null) throw new IllegalArgumentException("raw source bytes required");
            bytes = bytes.clone();
        }
        @Override public byte[] bytes() { return bytes.clone(); }
    }

    /** Only known transient source outages should be retried. */
    class RetryableSourceException extends RuntimeException {
        public RetryableSourceException(String reasonCode) {
            super(reasonCode);
        }
    }
}
