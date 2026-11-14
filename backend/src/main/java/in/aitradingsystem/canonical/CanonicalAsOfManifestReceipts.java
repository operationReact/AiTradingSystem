package in.aitradingsystem.canonical;

import in.aitradingsystem.marketdata.GoldenSourceRightsRepository;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Read-only persisted BRONZE receipt discovery for canonical consumers.
 * No object bytes, account information, broker requests or execution authority.
 * A receipt is metadata, NOT proof that an object is currently accessible.
 */
@Repository
public class CanonicalAsOfManifestReceipts {
    public static final String SCHEMA_VERSION = "minttick.canonical.manifest-receipts.v1";
    private final JdbcTemplate jdbc;
    private final GoldenSourceRightsRepository rights;

    public CanonicalAsOfManifestReceipts(
            JdbcTemplate jdbc, GoldenSourceRightsRepository rights) {
        this.jdbc = Objects.requireNonNull(jdbc);
        this.rights = Objects.requireNonNull(rights);
    }

    public record Receipt(UUID manifestId, UUID listingId, String provider,
                          String sourceDomain, String interval, int datasetRevision,
                          String datasetDigest, String schemaVersion,
                          Instant firstAvailableAt, Instant lastAvailableAt,
                          Instant publishedAt, long verifiedRows) {}

    /**
     * Enumerates at most 100 independently checkpointed, complete immutable
     * BRONZE receipts available at the research decision instant.
     * Rights are evaluated at the CURRENT usage instant, not retroactively
     * at the backtest instant. Consumers must independently verify object
     * bytes/version before reading; no historical candle is a live quote.
     */
    public List<Receipt> findResearchInputs(UUID listingId, String interval,
                                             Instant asOf, Instant usageAt) {
        if (listingId == null || interval == null
                || !interval.matches("[A-Za-z0-9_]{1,24}")
                || asOf == null || usageAt == null || asOf.isAfter(usageAt)) {
            throw new IllegalArgumentException("valid listing, interval and as-of times required");
        }
        List<Receipt> candidates = jdbc.query("""
                SELECT m.manifest_id, j.listing_id, p.provider_code,
                       j.source_domain, j.interval_name, j.dataset_revision,
                       m.manifest_digest, m.schema_version, c.first_available_at,
                       c.last_available_at, m.published_at, c.valid_records
                  FROM golden_dataset_object_manifest m
                  JOIN golden_dataset_backfill_chunk c ON c.chunk_id = m.chunk_id
                  JOIN golden_dataset_backfill_job j ON j.job_id = c.job_id
                  JOIN golden_data_provider p ON p.provider_id = j.provider_id
                 WHERE j.listing_id = ? AND j.interval_name = ?
                   AND p.source_kind = 'EXTERNAL'
                   AND m.layer = 'BRONZE'
                   AND c.status = 'VERIFIED'
                   AND c.expected_records > 0
                   AND c.valid_records = c.expected_records
                   AND c.dataset_digest = m.object_sha256
                   AND c.archive_uri = m.object_uri
                   AND c.last_available_at <= ?
                   AND m.published_at <= ?
                 ORDER BY m.published_at DESC, m.manifest_id
                 LIMIT 100
                """, (rs, row) -> map(rs), listingId, interval, utc(asOf), utc(asOf));
        return candidates.stream().filter(row ->
                rights.isPermitted(row.provider(), row.sourceDomain(),
                        GoldenSourceRightsRepository.Use.RAW_RETENTION, usageAt)
                && rights.isPermitted(row.provider(), row.sourceDomain(),
                        GoldenSourceRightsRepository.Use.MODEL_INFERENCE, usageAt))
                .toList();
    }

    private static Receipt map(ResultSet rs) throws SQLException {
        return new Receipt(rs.getObject("manifest_id", UUID.class),
                rs.getObject("listing_id", UUID.class),
                rs.getString("provider_code"), rs.getString("source_domain"),
                rs.getString("interval_name"), rs.getInt("dataset_revision"),
                rs.getString("manifest_digest"), rs.getString("schema_version"),
                rs.getObject("first_available_at", OffsetDateTime.class).toInstant(),
                rs.getObject("last_available_at", OffsetDateTime.class).toInstant(),
                rs.getObject("published_at", OffsetDateTime.class).toInstant(),
                rs.getLong("valid_records"));
    }

    private static OffsetDateTime utc(Instant time) {
        return OffsetDateTime.ofInstant(time, ZoneOffset.UTC);
    }
}
