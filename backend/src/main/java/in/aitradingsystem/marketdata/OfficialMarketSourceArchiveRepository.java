package in.aitradingsystem.marketdata;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class OfficialMarketSourceArchiveRepository {
    private final JdbcTemplate jdbc;

    public OfficialMarketSourceArchiveRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public OfficialMarketSourceSnapshot saveFirstObserved(
            UUID snapshotId,
            OfficialMarketSourceFamily family,
            String variant,
            String sourceUrl,
            Instant observedAt,
            String payloadSha256,
            String contentType,
            byte[] payload) {
        jdbc.update("""
                INSERT INTO official_market_source_snapshot
                    (snapshot_id, source_family, source_variant, source_url, observed_at,
                     payload_sha256, content_type, payload, byte_count)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (source_family, source_variant, payload_sha256) DO NOTHING
                """,
                snapshotId,
                family.name(),
                variant,
                sourceUrl,
                OffsetDateTime.ofInstant(observedAt, ZoneOffset.UTC),
                payloadSha256,
                contentType,
                payload,
                payload.length);
        return requireByPayload(family, variant, payloadSha256);
    }

    public OfficialMarketSourceSnapshot latestAt(
            OfficialMarketSourceFamily family,
            String variant,
            Instant asOf) {
        List<OfficialMarketSourceSnapshot> rows = jdbc.query("""
                SELECT *
                  FROM official_market_source_snapshot
                 WHERE source_family = ?
                   AND source_variant = ?
                   AND observed_at <= ?
                 ORDER BY observed_at DESC, snapshot_id DESC
                 LIMIT 1
                """, (rs, row) -> map(rs), family.name(), variant,
                OffsetDateTime.ofInstant(asOf, ZoneOffset.UTC));
        return rows.stream().findFirst().orElse(null);
    }

    public OfficialMarketSourceSnapshot requireByPayload(
            OfficialMarketSourceFamily family,
            String variant,
            String payloadSha256) {
        List<OfficialMarketSourceSnapshot> rows = jdbc.query("""
                SELECT *
                  FROM official_market_source_snapshot
                 WHERE source_family = ? AND source_variant = ? AND payload_sha256 = ?
                 LIMIT 1
                """, (rs, row) -> map(rs), family.name(), variant, payloadSha256);
        if (rows.isEmpty()) {
            throw new IllegalStateException("Official source snapshot was not persisted");
        }
        return rows.getFirst();
    }

    private OfficialMarketSourceSnapshot map(ResultSet rs) throws SQLException {
        return new OfficialMarketSourceSnapshot(
                rs.getObject("snapshot_id", UUID.class),
                OfficialMarketSourceFamily.valueOf(rs.getString("source_family")),
                rs.getString("source_variant"),
                rs.getString("source_url"),
                rs.getObject("observed_at", OffsetDateTime.class).toInstant(),
                rs.getString("payload_sha256"),
                rs.getString("content_type"),
                rs.getBytes("payload"),
                rs.getInt("byte_count"),
                rs.getObject("created_at", OffsetDateTime.class).toInstant());
    }
}
