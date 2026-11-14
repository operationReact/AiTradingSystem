package in.aitradingsystem.canonical;

import in.aitradingsystem.marketdata.GoldenSourceRightsRepository;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Persisted, read-only per-provider manifest receipts for a single listing and interval.
 * Counts are evidence of checkpointed metadata, never proof of physical object readback,
 * continuous coverage, or a live executable quote. Each verified chunk is counted
 * once even when multiple matching object-manifest receipts exist.
 */
@Repository
public class CanonicalManifestReceiptSummary {
    public static final String SCHEMA_VERSION = "minttick.canonical.manifest-summary.v1";

    private final JdbcTemplate jdbc;
    private final GoldenSourceRightsRepository rights;

    public CanonicalManifestReceiptSummary(JdbcTemplate jdbc, GoldenSourceRightsRepository rights) {
        this.jdbc = Objects.requireNonNull(jdbc);
        this.rights = Objects.requireNonNull(rights);
    }

    public record ProviderReceipt(String provider, String sourceDomain,
                                  long verifiedManifests, long verifiedRows,
                                  Instant firstAvailableAt, Instant lastAvailableAt) {}

    /**
     * Returns only currently rights-authorized, point-in-time available BRONZE
     * manifests. Empty means no eligible receipts, not zero attempted requests.
     * The input is deliberately scoped to avoid an unrestricted global scan.
     */
    public List<ProviderReceipt> findEligible(UUID listingId, String interval,
                                              Instant asOf, Instant usageAt) {
        if (listingId == null || interval == null
                || !interval.matches("[A-Za-z0-9_]{1,24}")
                || asOf == null || usageAt == null || asOf.isAfter(usageAt)) {
            throw new IllegalArgumentException("valid listing, interval and as-of times required");
        }
        List<ProviderReceipt> receipts = jdbc.query("""
                SELECT p.provider_code, j.source_domain,
                       COUNT(DISTINCT c.chunk_id) AS verified_manifests,
                       SUM(c.valid_records) AS verified_rows,
                       MIN(c.first_available_at) AS first_available_at,
                       MAX(c.last_available_at) AS last_available_at
                  FROM golden_dataset_backfill_chunk c
                  JOIN golden_dataset_backfill_job j ON j.job_id = c.job_id
                  JOIN golden_data_provider p ON p.provider_id = j.provider_id
                 WHERE j.listing_id = ? AND j.interval_name = ?
                   AND p.source_kind = 'EXTERNAL'
                   AND c.status = 'VERIFIED'
                   AND c.expected_records > 0
                   AND c.valid_records = c.expected_records
                   AND c.first_available_at IS NOT NULL
                   AND c.last_available_at IS NOT NULL
                   AND c.first_available_at <= c.last_available_at
                   AND c.last_available_at <= ?
                   AND EXISTS (
                       SELECT 1 FROM golden_dataset_object_manifest m
                        WHERE m.chunk_id = c.chunk_id
                          AND m.layer = 'BRONZE'
                          AND m.object_sha256 = c.dataset_digest
                          AND m.object_uri = c.archive_uri
                          AND m.published_at IS NOT NULL
                          AND m.published_at >= c.last_available_at
                          AND m.published_at <= ?
                   )
                 GROUP BY p.provider_code, j.source_domain
                 ORDER BY p.provider_code, j.source_domain
                 LIMIT 100
                """, (rs, row) -> new ProviderReceipt(
                        rs.getString("provider_code"), rs.getString("source_domain"),
                        rs.getLong("verified_manifests"), rs.getLong("verified_rows"),
                        rs.getObject("first_available_at", OffsetDateTime.class).toInstant(),
                        rs.getObject("last_available_at", OffsetDateTime.class).toInstant()),
                listingId, interval, OffsetDateTime.ofInstant(asOf, ZoneOffset.UTC),
                OffsetDateTime.ofInstant(asOf, ZoneOffset.UTC));
        return receipts.stream().filter(r ->
                rights.isPermitted(r.provider(), r.sourceDomain(),
                        GoldenSourceRightsRepository.Use.RAW_RETENTION, usageAt)
                && rights.isPermitted(r.provider(), r.sourceDomain(),
                        GoldenSourceRightsRepository.Use.MODEL_INFERENCE, usageAt)).toList();
    }
}
