package in.aitradingsystem.marketdata;

import java.net.URI;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;
import software.amazon.awssdk.core.ResponseBytes;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.BucketLocationConstraint;
import software.amazon.awssdk.services.s3.model.BucketVersioningStatus;
import software.amazon.awssdk.services.s3.model.GetBucketEncryptionRequest;
import software.amazon.awssdk.services.s3.model.GetBucketLocationRequest;
import software.amazon.awssdk.services.s3.model.GetBucketVersioningRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetPublicAccessBlockRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectResponse;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectResponse;
import software.amazon.awssdk.services.s3.model.S3Exception;
import software.amazon.awssdk.services.s3.model.ServerSideEncryption;

/**
 * D20-04B opt-in, research-only, AWS-side immutable raw archival adapter.
 *
 * Requirements: pre-existing private ap-south-1 bucket, enabled versioning,
 * customer-managed SSE-KMS default, full S3 public-access block and exact
 * bucket-owner pin. The adapter does not create buckets, fetch market history
 * or grant rights. It intentionally does not implement any order API.
 */
public final class GoldenS3ArchiveAdapter
        implements GoldenChunkArchive, GoldenObjectInspector {
    private static final Pattern BUCKET = Pattern.compile(
            "[a-z0-9][a-z0-9.-]{1,61}[a-z0-9]");
    private static final Pattern HASH = Pattern.compile("[a-f0-9]{64}");
    private static final Pattern VERSION = Pattern.compile("[A-Za-z0-9._~+/-]{1,240}");
    private static final Pattern PREFIX = Pattern.compile("[a-z0-9][a-z0-9_./-]{1,180}/");
    private static final int MAX_BYTES = GoldenSourceBackfillCoordinator.MAX_BYTES;

    private final S3Client s3;
    private final String bucket;
    private final String owner;
    private final String kmsKeyArn;
    private final String prefix;

    /** No S3 call is made unless the surrounding opt-in Spring bean is created. */
    public GoldenS3ArchiveAdapter(
            S3Client s3, String bucket, String expectedBucketOwner,
            String kmsKeyArn, String prefix) {
        this.s3 = Objects.requireNonNull(s3);
        this.bucket = Objects.requireNonNull(bucket);
        this.owner = Objects.requireNonNull(expectedBucketOwner);
        this.kmsKeyArn = Objects.requireNonNull(kmsKeyArn);
        this.prefix = Objects.requireNonNull(prefix);
        if (!BUCKET.matcher(bucket).matches() || bucket.contains("..")
                || !owner.matches("[0-9]{12}")
                || !kmsKeyArn.matches("arn:aws:kms:ap-south-1:" + owner
                        + ":key/[a-zA-Z0-9-]{1,100}")
                || !PREFIX.matcher(prefix).matches()
                || prefix.contains("..") || prefix.contains("//")) {
            throw new IllegalArgumentException("invalid restricted Golden Source S3 configuration");
        }
        verifyBucketPreconditions();
    }

    /** Fail closed if a pre-existing bucket has incorrect region or security. */
    private void verifyBucketPreconditions() {
        var location = s3.getBucketLocation(GetBucketLocationRequest.builder()
                .bucket(bucket).expectedBucketOwner(owner).build());
        if (location.locationConstraint() != BucketLocationConstraint.AP_SOUTH_1) {
            throw new IllegalStateException("Golden Source bucket must be in ap-south-1");
        }
        var versioning = s3.getBucketVersioning(GetBucketVersioningRequest.builder()
                .bucket(bucket).expectedBucketOwner(owner).build());
        if (versioning.status() != BucketVersioningStatus.ENABLED) {
            throw new IllegalStateException("Golden Source bucket versioning must be enabled");
        }
        var access = s3.getPublicAccessBlock(GetPublicAccessBlockRequest.builder()
                .bucket(bucket).expectedBucketOwner(owner).build())
                .publicAccessBlockConfiguration();
        if (access == null
                || !Boolean.TRUE.equals(access.blockPublicAcls())
                || !Boolean.TRUE.equals(access.ignorePublicAcls())
                || !Boolean.TRUE.equals(access.blockPublicPolicy())
                || !Boolean.TRUE.equals(access.restrictPublicBuckets())) {
            throw new IllegalStateException("Golden Source bucket public access is not blocked");
        }
        var encryption = s3.getBucketEncryption(GetBucketEncryptionRequest.builder()
                .bucket(bucket).expectedBucketOwner(owner).build())
                .serverSideEncryptionConfiguration();
        if (encryption == null || encryption.rules().isEmpty()
                || encryption.rules().stream().noneMatch(rule ->
                    rule.applyServerSideEncryptionByDefault() != null
                    && rule.applyServerSideEncryptionByDefault().sseAlgorithm()
                        == ServerSideEncryption.AWS_KMS
                    && kmsKeyArn.equals(
                        rule.applyServerSideEncryptionByDefault().kmsMasterKeyID()))) {
            throw new IllegalStateException("Golden Source bucket requires the pinned SSE-KMS key");
        }
    }

    /**
     * Content-addressed, idempotent per chunk. A completed durable version is
     * re-read and hashed before acknowledgment. AWS ETags are NOT checksums.
     */
    @Override
    public ArchiveReceipt putImmutable(
            GoldenHistoricalDatasetProvider.ChunkRequest request,
            byte[] bytes, String expectedSha256) {
        Objects.requireNonNull(request);
        if (request.jobId() == null || request.chunkId() == null
                || bytes == null || bytes.length == 0 || bytes.length > MAX_BYTES
                || request.maxBytes() <= 0 || bytes.length > request.maxBytes()
                || !HASH.matcher(Objects.requireNonNull(expectedSha256)).matches()) {
            throw new IllegalArgumentException("invalid bounded archive payload");
        }
        String hash = sha256(bytes);
        if (!hash.equals(expectedSha256)) {
            throw new IllegalArgumentException("archive source checksum mismatch");
        }
        String key = key(request, hash);

        // Reuse a previous committed object version after process restart.
        // HEAD 403 is *not* treated as missing: that is a permissions failure.
        HeadObjectResponse previous = null;
        try {
            previous = s3.headObject(HeadObjectRequest.builder()
                    .bucket(bucket).key(key).expectedBucketOwner(owner).build());
        } catch (S3Exception error) {
            if (error.statusCode() != 404) throw error;
        }
        String version;
        if (previous == null) {
            byte[] shaBytes = hexBytes(hash);
            PutObjectResponse written = s3.putObject(
                    PutObjectRequest.builder()
                            .bucket(bucket).key(key).expectedBucketOwner(owner)
                            .serverSideEncryption(ServerSideEncryption.AWS_KMS)
                            .ssekmsKeyId(kmsKeyArn)
                            .contentType("application/octet-stream")
                            .checksumSHA256(Base64.getEncoder().encodeToString(shaBytes))
                            .build(),
                    RequestBody.fromBytes(bytes));
            version = written.versionId();
        } else {
            version = previous.versionId();
        }
        if (version == null || version.isBlank() || "null".equals(version)
                || !VERSION.matcher(version).matches()) {
            throw new IllegalStateException("no pinned immutable S3 version ID");
        }
        String uri = formatUri(key, version);
        // Always verify a pinned version through GET and a locally computed
        // SHA-256 before handing its URI to D20-03B durable checkpoints.
        InspectedObject inspected = inspect(uri);
        if (!hash.equals(inspected.sha256()) || inspected.sizeBytes() != bytes.length
                || !version.equals(inspected.versionId())) {
            throw new IllegalStateException("S3 readback mismatch, archive not acknowledged");
        }
        return new ArchiveReceipt(uri, hash);
    }

    /** Read only an explicit pinned version from this exact owned bucket/prefix. */
    @Override
    public InspectedObject inspect(String objectUri) {
        ParsedObject parsed = parseUri(objectUri);
        HeadObjectResponse head = s3.headObject(HeadObjectRequest.builder()
                .bucket(bucket).key(parsed.key()).versionId(parsed.version())
                .expectedBucketOwner(owner).build());
        if (head.contentLength() == null || head.contentLength() <= 0
                || head.contentLength() > MAX_BYTES
                || !parsed.version().equals(head.versionId())
                || head.serverSideEncryption() != ServerSideEncryption.AWS_KMS
                || !kmsKeyArn.equals(head.ssekmsKeyId())) {
            throw new IllegalStateException("S3 object size, KMS or version verification failed");
        }
        ResponseBytes<?> received = s3.getObjectAsBytes(GetObjectRequest.builder()
                .bucket(bucket).key(parsed.key()).versionId(parsed.version())
                .expectedBucketOwner(owner).build());
        byte[] bytes = received.asByteArray();
        if (bytes.length != head.contentLength()
                || bytes.length == 0 || bytes.length > MAX_BYTES) {
            throw new IllegalStateException("S3 readback object length mismatch");
        }
        return new InspectedObject(parsed.version(), sha256(bytes), bytes.length);
    }

    private String key(GoldenHistoricalDatasetProvider.ChunkRequest request, String hash) {
        return prefix + request.jobId() + "/" + request.chunkId() + "/" + hash + ".bin";
    }

    private String formatUri(String key, String version) {
        return "s3://" + bucket + "/" + key + "?versionId="
                + URLEncoder.encode(version, StandardCharsets.UTF_8);
    }

    private ParsedObject parseUri(String raw) {
        try {
            URI uri = URI.create(Objects.requireNonNull(raw));
            if (!"s3".equals(uri.getScheme()) || !bucket.equals(uri.getHost())
                    || uri.getPort() != -1 || uri.getUserInfo() != null
                    || uri.getFragment() != null
                    || uri.getRawPath() == null || !uri.getRawPath().startsWith("/" + prefix)
                    || uri.getRawQuery() == null
                    || !uri.getRawQuery().startsWith("versionId=")
                    || uri.getRawQuery().contains("&")) {
                throw new IllegalArgumentException("untrusted S3 object locator");
            }
            String key = uri.getRawPath().substring(1);
            String version = URLDecoder.decode(
                    uri.getRawQuery().substring("versionId=".length()), StandardCharsets.UTF_8);
            String suffix = key.substring(prefix.length());
            // Immutable, unambiguous path. Never follow arbitrary URI or key.
            if (!suffix.matches(
                    "[0-9a-f-]{36}/[0-9a-f-]{36}/[a-f0-9]{64}\\.bin")
                    || !VERSION.matcher(version).matches()) {
                throw new IllegalArgumentException("invalid S3 version or storage path");
            }
            return new ParsedObject(key, version);
        } catch (RuntimeException error) {
            throw new IllegalArgumentException("invalid pinned Golden Source archive URI");
        }
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 unavailable", impossible);
        }
    }

    private static byte[] hexBytes(String hash) {
        return HexFormat.of().parseHex(hash);
    }

    private record ParsedObject(String key, String version) {}
}
