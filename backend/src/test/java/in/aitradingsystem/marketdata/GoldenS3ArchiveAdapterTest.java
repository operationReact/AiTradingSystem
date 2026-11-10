package in.aitradingsystem.marketdata;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import software.amazon.awssdk.core.ResponseBytes;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.BucketLocationConstraint;
import software.amazon.awssdk.services.s3.model.BucketVersioningStatus;
import software.amazon.awssdk.services.s3.model.GetBucketEncryptionResponse;
import software.amazon.awssdk.services.s3.model.GetBucketLocationResponse;
import software.amazon.awssdk.services.s3.model.GetBucketVersioningResponse;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.GetPublicAccessBlockResponse;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectResponse;
import software.amazon.awssdk.services.s3.model.PublicAccessBlockConfiguration;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectResponse;
import software.amazon.awssdk.services.s3.model.S3Exception;
import software.amazon.awssdk.services.s3.model.ServerSideEncryption;
import software.amazon.awssdk.services.s3.model.ServerSideEncryptionByDefault;
import software.amazon.awssdk.services.s3.model.ServerSideEncryptionConfiguration;
import software.amazon.awssdk.services.s3.model.ServerSideEncryptionRule;

/** No AWS calls: only a mocked S3 client and synthetic archive bytes. */
class GoldenS3ArchiveAdapterTest {
    private static final String BUCKET = "minttick-golden-source-fixture";
    private static final String OWNER = "098859530205";
    private static final String KMS = "arn:aws:kms:ap-south-1:" + OWNER
            + ":key/11111111-2222-3333-4444-555555555555";
    private static final String PREFIX = "golden-source/bronze/v1/";
    private static final String VERSION = "a.Version+001";
    private static final byte[] BYTES = "synthetic-test-bars".getBytes(StandardCharsets.UTF_8);

    @Test
    void missingBucketOwnershipOrMalformedKmsConfigNeverCallsAws() {
        S3Client s3 = mock(S3Client.class);
        assertThatThrownBy(() -> new GoldenS3ArchiveAdapter(s3, BUCKET,
                "", KMS, PREFIX)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new GoldenS3ArchiveAdapter(s3, BUCKET,
                OWNER, "arn:aws:kms:us-east-1:" + OWNER + ":key/bad", PREFIX))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new GoldenS3ArchiveAdapter(s3, BUCKET,
                OWNER, KMS, "../archive/")).isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(s3);
    }

    @Test
    void refusesUnversionedBucketAndPublicAccessConfiguration() {
        S3Client s3 = configured();
        when(s3.getBucketVersioning(any(software.amazon.awssdk.services.s3.model
                .GetBucketVersioningRequest.class))).thenReturn(
                GetBucketVersioningResponse.builder()
                        .status(BucketVersioningStatus.SUSPENDED).build());
        assertThatThrownBy(() -> adapter(s3))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("versioning");

        S3Client open = configured();
        when(open.getPublicAccessBlock(any(software.amazon.awssdk.services.s3.model
                .GetPublicAccessBlockRequest.class))).thenReturn(
                GetPublicAccessBlockResponse.builder()
                    .publicAccessBlockConfiguration(PublicAccessBlockConfiguration.builder()
                        .blockPublicAcls(false).ignorePublicAcls(true)
                        .blockPublicPolicy(true).restrictPublicBuckets(true).build())
                    .build());
        assertThatThrownBy(() -> adapter(open))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("public access");
    }

    @Test
    void rejectsForeignRegionAndWrongEncryptionKey() {
        S3Client s3 = configured();
        when(s3.getBucketLocation(any(software.amazon.awssdk.services.s3.model
                .GetBucketLocationRequest.class))).thenReturn(
                GetBucketLocationResponse.builder()
                        .locationConstraint(BucketLocationConstraint.US_EAST_2).build());
        assertThatThrownBy(() -> adapter(s3))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("ap-south-1");

        S3Client wrongKey = configured();
        when(wrongKey.getBucketEncryption(any(software.amazon.awssdk.services.s3.model
                .GetBucketEncryptionRequest.class))).thenReturn(
                GetBucketEncryptionResponse.builder()
                    .serverSideEncryptionConfiguration(
                        ServerSideEncryptionConfiguration.builder().rules(
                            ServerSideEncryptionRule.builder()
                                .applyServerSideEncryptionByDefault(
                                    ServerSideEncryptionByDefault.builder()
                                        .sseAlgorithm(ServerSideEncryption.AWS_KMS)
                                        .kmsMasterKeyID("another-key").build())
                                .build()).build()).build());
        assertThatThrownBy(() -> adapter(wrongKey))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("KMS");
    }

    @Test
    void writesImmutablePinnedVersionAndIndependentlyRehashesReadback() throws Exception {
        S3Client s3 = configured();
        archiveCalls(s3, BYTES, VERSION, false);
        GoldenS3ArchiveAdapter archive = adapter(s3);
        var request = request();
        String hash = sha256(BYTES);
        var receipt = archive.putImmutable(request, BYTES, hash);
        assertThat(receipt.persistedSha256()).isEqualTo(hash);
        assertThat(receipt.objectUri()).startsWith(
                "s3://" + BUCKET + "/" + PREFIX + request.jobId());
        assertThat(receipt.objectUri()).contains("versionId=");
        var inspected = archive.inspect(receipt.objectUri());
        assertThat(inspected.sha256()).isEqualTo(hash);
        assertThat(inspected.sizeBytes()).isEqualTo(BYTES.length);
        assertThat(inspected.versionId()).isEqualTo(VERSION);
        ArgumentCaptor<PutObjectRequest> written = ArgumentCaptor.forClass(PutObjectRequest.class);
        verify(s3).putObject(written.capture(), any(RequestBody.class));
        assertThat(written.getValue().expectedBucketOwner()).isEqualTo(OWNER);
        assertThat(written.getValue().serverSideEncryption()).isEqualTo(ServerSideEncryption.AWS_KMS);
        assertThat(written.getValue().ssekmsKeyId()).isEqualTo(KMS);
        assertThat(written.getValue().checksumSHA256()).isNotBlank();
    }

    @Test
    void rejectsProviderChecksumMismatchWithoutUpload() {
        S3Client s3 = configured();
        GoldenS3ArchiveAdapter archive = adapter(s3);
        assertThatThrownBy(() -> archive.putImmutable(request(),
                BYTES, "f".repeat(64))).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void corruptedReadbackCannotAcknowledgeSource() {
        S3Client s3 = configured();
        archiveCalls(s3, "corrupted".getBytes(StandardCharsets.UTF_8),
                VERSION, false);
        var archive = adapter(s3);
        assertThatThrownBy(() -> archive.putImmutable(request(), BYTES, sha256(BYTES)))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void mustPinVersionAndStayInApprovedBucketAndContentAddressedPrefix() {
        S3Client s3 = configured();
        var archive = adapter(s3);
        for (String invalid : new String[] {
                "s3://" + BUCKET + "/golden-source/bronze/v1/object",
                "s3://different-bucket/" + PREFIX + "object?versionId=123",
                "https://" + BUCKET + "/" + PREFIX + "object?versionId=123",
                "s3://" + BUCKET + "/" + PREFIX + "../secret?versionId=123",
                "s3://" + BUCKET + "/" + PREFIX + "object?versionId=123&x=bad"
        }) {
            assertThatThrownBy(() -> archive.inspect(invalid))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test
    void missingVersionIdCannotPretendToHaveImmutableSource() {
        S3Client s3 = configured();
        archiveCalls(s3, BYTES, "", false);
        GoldenS3ArchiveAdapter archive = adapter(s3);
        assertThatThrownBy(() -> archive.putImmutable(request(), BYTES, sha256(BYTES)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("version ID");
    }

    private static S3Client configured() {
        S3Client s3 = mock(S3Client.class);
        when(s3.getBucketLocation(any(software.amazon.awssdk.services.s3.model
                .GetBucketLocationRequest.class))).thenReturn(
                GetBucketLocationResponse.builder()
                    .locationConstraint(BucketLocationConstraint.AP_SOUTH_1).build());
        when(s3.getBucketVersioning(any(software.amazon.awssdk.services.s3.model
                .GetBucketVersioningRequest.class))).thenReturn(
                GetBucketVersioningResponse.builder()
                    .status(BucketVersioningStatus.ENABLED).build());
        when(s3.getPublicAccessBlock(any(software.amazon.awssdk.services.s3.model
                .GetPublicAccessBlockRequest.class))).thenReturn(
                GetPublicAccessBlockResponse.builder()
                    .publicAccessBlockConfiguration(PublicAccessBlockConfiguration.builder()
                        .blockPublicAcls(true).ignorePublicAcls(true)
                        .blockPublicPolicy(true).restrictPublicBuckets(true).build())
                    .build());
        when(s3.getBucketEncryption(any(software.amazon.awssdk.services.s3.model
                .GetBucketEncryptionRequest.class))).thenReturn(
                GetBucketEncryptionResponse.builder()
                    .serverSideEncryptionConfiguration(
                        ServerSideEncryptionConfiguration.builder().rules(
                            ServerSideEncryptionRule.builder()
                                .applyServerSideEncryptionByDefault(
                                    ServerSideEncryptionByDefault.builder()
                                        .sseAlgorithm(ServerSideEncryption.AWS_KMS)
                                        .kmsMasterKeyID(KMS).build())
                                .build()).build()).build());
        return s3;
    }

    private static void archiveCalls(S3Client s3, byte[] returnedBytes,
                                     String version, boolean existing) {
        when(s3.headObject(any(HeadObjectRequest.class))).thenAnswer(invocation -> {
            HeadObjectRequest req = invocation.getArgument(0);
            if (req.versionId() == null) {
                if (!existing) throw S3Exception.builder().statusCode(404).build();
            }
            return HeadObjectResponse.builder()
                    .versionId(version).contentLength((long) BYTES.length)
                    .serverSideEncryption(ServerSideEncryption.AWS_KMS)
                    .ssekmsKeyId(KMS).build();
        });
        when(s3.putObject(any(PutObjectRequest.class), any(RequestBody.class)))
                .thenReturn(PutObjectResponse.builder().versionId(version).build());
        when(s3.getObjectAsBytes(any(GetObjectRequest.class)))
                .thenReturn(ResponseBytes.fromByteArray(
                        GetObjectResponse.builder().versionId(version).build(),
                        returnedBytes));
    }

    private static GoldenS3ArchiveAdapter adapter(S3Client s3) {
        return new GoldenS3ArchiveAdapter(s3, BUCKET, OWNER, KMS, PREFIX);
    }

    private static GoldenHistoricalDatasetProvider.ChunkRequest request() {
        return new GoldenHistoricalDatasetProvider.ChunkRequest(
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), "ONE_DAY",
                java.time.LocalDate.parse("2026-11-01"),
                java.time.LocalDate.parse("2026-11-10"), 4096);
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (Exception impossible) {
            throw new AssertionError(impossible);
        }
    }
}
