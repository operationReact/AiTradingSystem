package in.aitradingsystem.instrument;

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
public class InstrumentCatalogueResearchRepository {
    private final JdbcTemplate jdbc;

    public InstrumentCatalogueResearchRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Latest successful catalogue whose publication completed no later than the research decision time. */
    public CatalogueResearchVersion requireVersionAt(Instant asOf) {
        List<CatalogueResearchVersion> rows = jdbc.query("""
                SELECT id, completed_at, source_url, accepted_count, rejected_count
                  FROM instrument_import_run
                 WHERE status = 'SUCCEEDED'
                   AND completed_at IS NOT NULL
                   AND completed_at <= ?
                 ORDER BY completed_at DESC, id DESC
                 LIMIT 1
                """, (rs, row) -> new CatalogueResearchVersion(
                rs.getObject("id", UUID.class),
                rs.getObject("completed_at", OffsetDateTime.class).toInstant(),
                rs.getString("source_url"),
                rs.getInt("accepted_count"),
                rs.getInt("rejected_count")), OffsetDateTime.ofInstant(asOf, ZoneOffset.UTC));
        if (rows.isEmpty()) {
            throw new InstrumentMasterException("CATALOGUE_NOT_AVAILABLE_AT_AS_OF",
                    "No successfully published instrument catalogue was available at the requested asOf");
        }
        return rows.get(0);
    }

    /**
     * Returns only India cash-market equity candidates from long-horizon immutable history.
     * Liquidity qualification belongs to a later V3 universe step.  The caller supplies maxRows + 1
     * so oversized evidence fails instead of being silently truncated.
     */
    public List<InstrumentCatalogueResearchExport.ResearchInstrument> cashEquities(UUID catalogueVersion, int limit) {
        return jdbc.query("""
                SELECT instrument_id, exchange_segment, exchange, symbol_token, trading_symbol,
                       instrument_name, instrument_type, expiry, strike, lot_size, tick_size
                  FROM instrument_cash_catalogue_history
                 WHERE catalogue_version = ?
                 ORDER BY exchange ASC, trading_symbol ASC, symbol_token ASC, instrument_id ASC
                 LIMIT ?
                """, (rs, row) -> mapInstrument(rs, row), catalogueVersion, limit);
    }

    private InstrumentCatalogueResearchExport.ResearchInstrument mapInstrument(ResultSet rs, int row)
            throws SQLException {
        return new InstrumentCatalogueResearchExport.ResearchInstrument(
                rs.getLong("instrument_id"),
                rs.getString("exchange_segment"),
                rs.getString("exchange"),
                rs.getString("symbol_token"),
                rs.getString("trading_symbol"),
                rs.getString("instrument_name"),
                rs.getString("instrument_type"),
                rs.getObject("expiry", java.time.LocalDate.class),
                rs.getBigDecimal("strike"),
                rs.getInt("lot_size"),
                rs.getBigDecimal("tick_size"));
    }

    public record CatalogueResearchVersion(
            UUID version,
            Instant availableAt,
            String sourceUrl,
            int acceptedCount,
            int rejectedCount) {}
}
