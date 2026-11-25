package in.aitradingsystem.marketdata;

/**
 * Immutable content-addressed archival sink. Implementations must atomically
 * persist source bytes, verify checksum after write, and return the permanent
 * content URI only after durability. No production S3 sink is wired in D20-03B.
 */
public interface GoldenChunkArchive {
    ArchiveReceipt putImmutable(GoldenHistoricalDatasetProvider.ChunkRequest request,
                                byte[] sourceBytes, String sha256);

    record ArchiveReceipt(String objectUri, String persistedSha256) {}
}
