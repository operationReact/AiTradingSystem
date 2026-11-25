package in.aitradingsystem.marketdata;

/**
 * D20-04A independent readback of an already archived immutable object.
 * A real implementation must verify a pinned object version's bytes and
 * compute SHA-256 independently of the provider/archiver receipt.
 *
 * No production S3 adapter, storage keys or AWS resources are installed.
 */
public interface GoldenObjectInspector {
    InspectedObject inspect(String immutableObjectUri);

    record InspectedObject(String versionId, String sha256, long sizeBytes) {}
}
