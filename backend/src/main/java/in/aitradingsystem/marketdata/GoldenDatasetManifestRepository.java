package in.aitradingsystem.marketdata;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * D20-04A internal, research-only immutable BRONZE manifest publication.
 *
 * A validated chunk alone does not establish durable storage. Publishing
 * additionally requires a separate physical readback, a stable object version
 * and full checksum equality. No public API or production S3 adapter exists.
 */
@Repository
public class GoldenDatasetManifestRepository {
    private static final String HASH = "[a-f0-9]{64}";
    private final JdbcTemplate jdbc;
    private final GoldenSourceRightsRepository rights;
    private final Clock clock;

    @Autowired
    public GoldenDatasetManifestRepository(
            JdbcTemplate jdbc, GoldenSourceRightsRepository rights) {
        this(jdbc, rights, Clock.systemUTC());
    }

    GoldenDatasetManifestRepository(
            JdbcTemplate jdbc, GoldenSourceRightsRepository rights, Clock clock) {
        this.jdbc = Objects.requireNonNull(jdbc);
        this.rights = Objects.requireNonNull(rights);
        this.clock = Objects.requireNonNull(clock);
    }

    /**
     * Metadata-only publication after a verified D20-03B checkpoint.
     * Reads object bytes through a separately reviewed inspection adapter.
     * It cannot grant rights, upload bytes, or produce a SILVER/GOLD dataset.
     */
    public Manifest publishBronze(
            UUID verifiedChunkId, GoldenObjectInspector inspector, String schemaVersion) {
        if (verifiedChunkId == null || inspector == null
                || schemaVersion == null
                || !schemaVersion.matches("[A-Za-z0-9._-]{1,120}")) {
            throw new IllegalArgumentException("verified chunk, inspector and schema required");
        }
        Chunk chunk = findVerifiedChunk(verifiedChunkId)
                .orElseThrow(() -> new IllegalArgumentException(
                        "fully verified durable source chunk required"));
        requireRetention(chunk);
        var inspected = Objects.requireNonNull(
                inspector.inspect(chunk.objectUri()), "independent storage readback required");
        if (inspected.versionId() == null
                || !inspected.versionId().matches("[A-Za-z0-9._:/+-]{1,240}")
                || inspected.sizeBytes() <= 0
                || inspected.sizeBytes() > Long.MAX_VALUE / 2
                || inspected.sha256() == null
                || !inspected.sha256().matches(HASH)
                || !inspected.sha256().equals(chunk.objectSha256())) {
            throw new IllegalStateException("physical archive evidence does not match checkpoint");
        }
        String digest = manifestDigest(chunk, inspected, schemaVersion);
        // A revoked permit after inspection still prohibits publishing.
        requireRetention(chunk);
        Instant publishedAt = clock.instant();
        UUID manifestId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO golden_dataset_object_manifest
                    (manifest_id, chunk_id, layer, parent_manifest_id,
                     manifest_digest, object_uri, object_version_id,
                     object_sha256, object_size_bytes, schema_version,
                     published_at, source_evidence_digest)
                VALUES (?, ?, 'BRONZE', NULL, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT DO NOTHING
                """, manifestId, chunk.chunkId(), digest, chunk.objectUri(),
                inspected.versionId(), inspected.sha256(), inspected.sizeBytes(),
                schemaVersion, utc(publishedAt), chunk.sourceEvidenceDigest());
        Manifest stored = findManifestByChunk(chunk.chunkId())
                .orElseThrow(() -> new IllegalStateException(
                        "unable to publish verified immutable manifest"));
        // Duplicate publishing is idempotent only if the full byte/version
        // fingerprint agrees. Corrections require a new dataset revision.
        if (!digest.equals(stored.manifestDigest())) {
            throw new IllegalStateException(
                    "conflicting immutable manifest; new source revision required");
        }
        return stored;
    }

    /**
     * Return only metadata from a verified BRONZE manifest if both retention
     * and the independently authorized consumer use are currently permitted.
     * Consumers must verify bytes against the pinned version and SHA on read.
     */
    public Optional<Manifest> findAuthorizedBronze(
            UUID manifestId, GoldenSourceRightsRepository.Use use) {
        if (manifestId == null || use == null)
            throw new IllegalArgumentException("manifest and permitted use required");
        var scoped = jdbc.query("""
                SELECT m.manifest_id, m.chunk_id, m.manifest_digest,
                       m.object_uri, m.object_version_id, m.object_sha256,
                       m.object_size_bytes, m.schema_version, m.published_at,
                       p.provider_code, j.source_domain
                  FROM golden_dataset_object_manifest m
                  JOIN golden_dataset_backfill_chunk c ON c.chunk_id = m.chunk_id
                  JOIN golden_dataset_backfill_job j ON j.job_id = c.job_id
                  JOIN golden_data_provider p ON p.provider_id = j.provider_id
                 WHERE m.manifest_id = ? AND m.layer = 'BRONZE'
                   AND c.status = 'VERIFIED'
                   AND c.dataset_digest = m.object_sha256
                   AND c.archive_uri = m.object_uri
                """, (rs, ignored) -> new ScopedManifest(
                        map(rs), rs.getString("provider_code"),
                        rs.getString("source_domain")), manifestId);
        if (scoped.size() != 1) return Optional.empty();
        ScopedManifest s = scoped.get(0);
        Instant now = clock.instant();
        if (!rights.isPermitted(s.providerCode(), s.sourceDomain(),
                GoldenSourceRightsRepository.Use.RAW_RETENTION, now)
                || !rights.isPermitted(s.providerCode(), s.sourceDomain(), use, now)) {
            return Optional.empty();
        }
        return Optional.of(s.manifest());
    }

    private Optional<Chunk> findVerifiedChunk(UUID id) {
        List<Chunk> result = jdbc.query("""
                SELECT c.chunk_id, c.archive_uri, c.dataset_digest,
                       c.source_evidence_digest, c.first_available_at,
                       c.last_available_at, c.from_date, c.through_date,
                       p.provider_code, j.source_domain, j.listing_id,
                       j.interval_name, j.dataset_revision
                  FROM golden_dataset_backfill_chunk c
                  JOIN golden_dataset_backfill_job j ON j.job_id = c.job_id
                  JOIN golden_data_provider p ON p.provider_id = j.provider_id
                 WHERE c.chunk_id = ? AND c.status = 'VERIFIED'
                   AND c.expected_records > 0
                   AND c.valid_records = c.expected_records
                """, (rs, ignored) -> new Chunk(
                        rs.getObject("chunk_id", UUID.class),
                        rs.getString("provider_code"),
                        rs.getString("source_domain"),
                        rs.getObject("listing_id", UUID.class),
                        rs.getString("interval_name"),
                        rs.getInt("dataset_revision"),
                        rs.getDate("from_date").toLocalDate().toString(),
                        rs.getDate("through_date").toLocalDate().toString(),
                        rs.getString("archive_uri"),
                        rs.getString("dataset_digest"),
                        rs.getString("source_evidence_digest"),
                        rs.getObject("first_available_at", OffsetDateTime.class),
                        rs.getObject("last_available_at", OffsetDateTime.class)), id);
        return result.size() == 1 ? Optional.of(result.get(0)) : Optional.empty();
    }

    private void requireRetention(Chunk chunk) {
        if (chunk.objectUri() == null || chunk.objectUri().isBlank()
                || chunk.objectSha256() == null
                || !chunk.objectSha256().matches(HASH)
                || chunk.sourceEvidenceDigest() == null
                || !chunk.sourceEvidenceDigest().matches(HASH)
                || chunk.firstAvailableAt() == null
                || chunk.lastAvailableAt() == null
                || !rights.isPermitted(chunk.providerCode(), chunk.sourceDomain(),
                    GoldenSourceRightsRepository.Use.RAW_RETENTION, clock.instant())) {
            throw new IllegalStateException(
                    "verified source, checksum, time and current retention rights required");
        }
    }

    private Optional<Manifest> findManifestByChunk(UUID chunkId) {
        List<Manifest> found = jdbc.query("""
                SELECT manifest_id, chunk_id, manifest_digest, object_uri,
                       object_version_id, object_sha256, object_size_bytes,
                       schema_version, published_at
                  FROM golden_dataset_object_manifest
                 WHERE chunk_id = ? AND layer = 'BRONZE'
                """, (rs, ignored) -> map(rs), chunkId);
        return found.size() == 1 ? Optional.of(found.get(0)) : Optional.empty();
    }

    private static Manifest map(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new Manifest(rs.getObject("manifest_id", UUID.class),
                rs.getObject("chunk_id", UUID.class),
                rs.getString("manifest_digest"),
                rs.getString("object_uri"),
                rs.getString("object_version_id"),
                rs.getString("object_sha256"),
                rs.getLong("object_size_bytes"),
                rs.getString("schema_version"),
                rs.getObject("published_at", OffsetDateTime.class).toInstant());
    }

    private static String manifestDigest(
            Chunk chunk, GoldenObjectInspector.InspectedObject inspected,
            String schemaVersion) {
        try {
            MessageDigest sha = MessageDigest.getInstance("SHA-256");
            // Length-prefix every component to avoid separator ambiguity.
            for (String field : new String[] {
                    "MintTickGoldenSource/BRONZE/v1", chunk.chunkId().toString(),
                    chunk.providerCode(), chunk.sourceDomain(),
                    chunk.listingId().toString(), chunk.intervalName(),
                    Integer.toString(chunk.datasetRevision()),
                    chunk.from(), chunk.through(), chunk.objectUri(),
                    inspected.versionId(), inspected.sha256(),
                    Long.toString(inspected.sizeBytes()), schemaVersion,
                    chunk.sourceEvidenceDigest(),
                    chunk.firstAvailableAt().toInstant().toString(),
                    chunk.lastAvailableAt().toInstant().toString()
            }) {
                byte[] bytes = field.getBytes(StandardCharsets.UTF_8);
                sha.update(ByteBuffer.allocate(Integer.BYTES).putInt(bytes.length).array());
                sha.update(bytes);
            }
            return HexFormat.of().formatHex(sha.digest());
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 unavailable", impossible);
        }
    }

    private static OffsetDateTime utc(Instant at) {
        return OffsetDateTime.ofInstant(at, ZoneOffset.UTC);
    }

    public record Manifest(UUID manifestId, UUID chunkId, String manifestDigest,
                           String objectUri, String objectVersionId,
                           String objectSha256, long objectSizeBytes,
                           String schemaVersion, Instant publishedAt) {}

    private record ScopedManifest(Manifest manifest, String providerCode,
                                  String sourceDomain) {}

    private record Chunk(UUID chunkId, String providerCode, String sourceDomain,
                         UUID listingId, String intervalName, int datasetRevision,
                         String from, String through, String objectUri,
                         String objectSha256, String sourceEvidenceDigest,
                         OffsetDateTime firstAvailableAt,
                         OffsetDateTime lastAvailableAt) {}
}
