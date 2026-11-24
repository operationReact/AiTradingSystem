package in.aitradingsystem.news.identity;

import java.sql.Date;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Read-only official BSE equity-ISIN evidence, using the latest source import
 * that was truly stored no later than a given research decision time.
 *
 * <p>Historical source availability uses imported_at conservatively; a source-as-of
 * date alone does not prove the document was known at that earlier time.
 * This is SECURITY (ISIN) evidence, not an attested legal issuer identifier.
 */
@Repository
public class NewsBseSecurityEvidenceRepository {
    static final int MAX_SOURCE_ROWS = 50_000;
    private final JdbcTemplate jdbc;

    public NewsBseSecurityEvidenceRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public List<SourceSecurity> latestAvailableAt(Instant decisionAt, LocalDate sourceDate) {
        return jdbc.query("""
                WITH latest_source AS (
                    SELECT id, source_as_of, imported_at, archive_sha256
                      FROM bse_security_master_import
                     WHERE imported_at <= ?
                       AND source_as_of <= ?
                     ORDER BY imported_at DESC, id DESC
                     LIMIT 1
                )
                SELECT e.scrip_code, e.security_id, e.isin,
                       s.id AS import_id, s.source_as_of, s.imported_at, s.archive_sha256
                  FROM latest_source s
                  JOIN bse_verified_cash_equity e ON e.import_id = s.id
                 ORDER BY e.scrip_code ASC
                 LIMIT ?
                """, (rs, row) -> new SourceSecurity(
                    rs.getString("scrip_code"), rs.getString("security_id"),
                    rs.getString("isin"), rs.getObject("import_id", UUID.class),
                    rs.getObject("source_as_of", LocalDate.class),
                    rs.getObject("imported_at", OffsetDateTime.class).toInstant(),
                    rs.getString("archive_sha256")),
                OffsetDateTime.ofInstant(decisionAt, ZoneOffset.UTC),
                Date.valueOf(sourceDate), MAX_SOURCE_ROWS + 1);
    }

    public record SourceSecurity(
        String scripCode, String securityId, String isin,
        UUID importId, LocalDate sourceAsOf, Instant firstAvailableAt,
        String archiveSha256) {}
}
