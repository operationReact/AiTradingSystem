package in.aitradingsystem.instrument;

import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Types;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Repository
public class InstrumentMasterRepository {
    private static final int BATCH_SIZE = 1_000;
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transaction;
    private final RowMapper<Instrument> instrumentMapper = (rs, row) -> new Instrument(
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
            rs.getBigDecimal("tick_size"),
            rs.getObject("catalogue_version", UUID.class));

    public InstrumentMasterRepository(JdbcTemplate jdbc, PlatformTransactionManager transactionManager) {
        this.jdbc = jdbc;
        this.transaction = new TransactionTemplate(transactionManager);
    }

    public void createImportRun(UUID id, String sourceUrl) {
        jdbc.update("INSERT INTO instrument_import_run (id, status, source_url) VALUES (?, 'RUNNING', ?)",
                id, sourceUrl);
    }

    public void failImport(UUID id, long bytes, int parsed, int accepted, int rejected, int duplicates,
                           String code, String message) {
        jdbc.update("""
                UPDATE instrument_import_run
                   SET status = 'FAILED', completed_at = now(), downloaded_bytes = ?,
                       parsed_count = ?, accepted_count = ?, rejected_count = ?, duplicate_count = ?,
                       error_code = ?, error_message = ?
                 WHERE id = ? AND status = 'RUNNING'
                """, bytes, parsed, accepted, rejected, duplicates, bounded(code, 80), bounded(message, 500), id);
    }

    public int publishCatalogue(UUID runId, List<InstrumentMasterParser.InstrumentDraft> instruments,
                                long bytes, int parsed, int rejected, int duplicates, int minimumRetainedPercent) {
        Integer activeCount = transaction.execute(status -> {
            CatalogueState current = lockCatalogueState();
            int previousCount = current.version() == null ? 0 : countForVersion(current.version());
            if (instruments.isEmpty()) {
                throw new InstrumentMasterException("NO_VALID_INSTRUMENTS", "No valid instruments were found");
            }
            if (previousCount > 0) {
                long minimum = (long) Math.ceil(previousCount * (minimumRetainedPercent / 100.0d));
                if (instruments.size() < minimum) {
                    throw new InstrumentMasterException("INCOMPLETE_CATALOGUE",
                            "Candidate catalogue is too small compared with the active catalogue");
                }
            }

            insertInBatches(runId, instruments);
            int publishedCount = instruments.size();
            jdbc.update("""
                    UPDATE instrument_import_run
                       SET status = 'SUCCEEDED', completed_at = now(), downloaded_bytes = ?,
                           parsed_count = ?, accepted_count = ?, rejected_count = ?, duplicate_count = ?,
                           active_count = ?, catalogue_version = ?, error_code = NULL, error_message = NULL
                     WHERE id = ? AND status = 'RUNNING'
                    """, bytes, parsed, publishedCount, rejected, duplicates, publishedCount, runId, runId);
            jdbc.update("""
                    UPDATE instrument_catalogue_state
                       SET active_version = ?, activated_at = now()
                     WHERE singleton_id = 1
                    """, runId);

            // Retain the active plus two previous successful catalogues; history rows stay for audit.
            jdbc.update("""
                    DELETE FROM instrument_master
                     WHERE catalogue_version IN (
                         SELECT id FROM instrument_import_run
                          WHERE status = 'SUCCEEDED' AND id <> ?
                          ORDER BY completed_at DESC NULLS LAST
                          OFFSET 2
                     )
                    """, runId);
            return publishedCount;
        });
        return activeCount == null ? 0 : activeCount;
    }

    private CatalogueState lockCatalogueState() {
        List<CatalogueState> rows = jdbc.query("""
                SELECT active_version, activated_at
                  FROM instrument_catalogue_state
                 WHERE singleton_id = 1
                 FOR UPDATE
                """, (rs, row) -> new CatalogueState(
                rs.getObject("active_version", UUID.class),
                rs.getObject("activated_at", java.time.OffsetDateTime.class) == null
                        ? null : rs.getObject("activated_at", java.time.OffsetDateTime.class).toInstant()));
        if (rows.isEmpty()) throw new InstrumentMasterException("CATALOGUE_STATE_MISSING",
                "Instrument catalogue state row is missing");
        return rows.get(0);
    }

    private int countForVersion(UUID version) {
        Integer count = jdbc.queryForObject(
                "SELECT count(*) FROM instrument_master WHERE catalogue_version = ? AND active", Integer.class, version);
        return count == null ? 0 : count;
    }

    private void insertInBatches(UUID runId, List<InstrumentMasterParser.InstrumentDraft> instruments) {
        String sql = """
                INSERT INTO instrument_master
                    (exchange_segment, exchange, symbol_token, trading_symbol, instrument_name, instrument_type,
                     expiry, strike, lot_size, tick_size, active, catalogue_version)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, TRUE, ?)
                """;
        for (int start = 0; start < instruments.size(); start += BATCH_SIZE) {
            List<InstrumentMasterParser.InstrumentDraft> chunk =
                    instruments.subList(start, Math.min(instruments.size(), start + BATCH_SIZE));
            jdbc.batchUpdate(sql, chunk, chunk.size(), (PreparedStatement ps,
                    InstrumentMasterParser.InstrumentDraft item) -> {
                ps.setString(1, item.exchangeSegment());
                ps.setString(2, item.exchange());
                ps.setString(3, item.symbolToken());
                ps.setString(4, item.tradingSymbol());
                ps.setString(5, item.name());
                ps.setString(6, item.instrumentType());
                if (item.expiry() == null) ps.setNull(7, Types.DATE); else ps.setObject(7, item.expiry());
                if (item.strike() == null) ps.setNull(8, Types.NUMERIC); else ps.setBigDecimal(8, item.strike());
                ps.setInt(9, item.lotSize());
                ps.setBigDecimal(10, item.tickSize());
                ps.setObject(11, runId);
            });
        }
    }

    public InstrumentImportSummary importSummary(UUID id) {
        List<InstrumentImportSummary> rows = jdbc.query("""
                SELECT id, status, started_at, completed_at, downloaded_bytes, parsed_count, accepted_count,
                       rejected_count, duplicate_count, active_count, error_code, error_message, catalogue_version
                  FROM instrument_import_run WHERE id = ?
                """, (rs, row) -> importMapper(rs), id);
        if (rows.isEmpty()) throw new InstrumentMasterException("IMPORT_NOT_FOUND", "Instrument import run was not found");
        return rows.get(0);
    }

    public List<InstrumentImportSummary> recentImports(int limit) {
        return jdbc.query("""
                SELECT id, status, started_at, completed_at, downloaded_bytes, parsed_count, accepted_count,
                       rejected_count, duplicate_count, active_count, error_code, error_message, catalogue_version
                  FROM instrument_import_run ORDER BY started_at DESC LIMIT ?
                """, (rs, row) -> importMapper(rs), limit);
    }

    private InstrumentImportSummary importMapper(java.sql.ResultSet rs) throws SQLException {
        return new InstrumentImportSummary(
                rs.getObject("id", UUID.class),
                rs.getString("status"),
                toInstant(rs.getObject("started_at", java.time.OffsetDateTime.class)),
                toInstant(rs.getObject("completed_at", java.time.OffsetDateTime.class)),
                rs.getLong("downloaded_bytes"),
                rs.getInt("parsed_count"),
                rs.getInt("accepted_count"),
                rs.getInt("rejected_count"),
                rs.getInt("duplicate_count"),
                rs.getInt("active_count"),
                rs.getString("error_code"),
                rs.getString("error_message"),
                rs.getObject("catalogue_version", UUID.class));
    }

    public InstrumentSearchResponse search(String q, String exchange, String type, int page, int size) {
        CatalogueState state = jdbc.query("""
                SELECT active_version, activated_at FROM instrument_catalogue_state WHERE singleton_id = 1
                """, (rs, row) -> new CatalogueState(
                rs.getObject("active_version", UUID.class),
                toInstant(rs.getObject("activated_at", java.time.OffsetDateTime.class))))
                .stream().findFirst().orElse(new CatalogueState(null, null));
        if (state.version() == null) return new InstrumentSearchResponse(List.of(), page, size, 0, 0, false, null, null);

        StringBuilder where = new StringBuilder(
                " WHERE i.active = TRUE AND i.catalogue_version = ? ");
        List<Object> args = new ArrayList<>();
        args.add(state.version());
        if (exchange != null && !exchange.isBlank()) {
            where.append(" AND i.exchange = ? ");
            args.add(exchange);
        }
        if (type != null && !type.isBlank()) {
            where.append(" AND i.instrument_type = ? ");
            args.add(type);
        }
        if (q != null && !q.isBlank()) {
            where.append(" AND (lower(i.trading_symbol) LIKE ? OR lower(coalesce(i.instrument_name, '')) LIKE ?) ");
            String query = q.toLowerCase(java.util.Locale.ROOT);
            args.add(query + "%");
            args.add("%" + query + "%");
        }
        Long total = jdbc.queryForObject("SELECT count(*) FROM instrument_master i" + where, Long.class,
                args.toArray());
        List<Object> pageArgs = new ArrayList<>(args);
        pageArgs.add(q == null ? "" : q);
        pageArgs.add(size);
        pageArgs.add((long) page * size);
        List<Instrument> content = jdbc.query("""
                SELECT i.instrument_id, i.exchange_segment, i.exchange, i.symbol_token, i.trading_symbol,
                       i.instrument_name, i.instrument_type, i.expiry, i.strike, i.lot_size, i.tick_size,
                       i.catalogue_version
                  FROM instrument_master i
                """ + where + """
                 ORDER BY CASE WHEN lower(i.trading_symbol) = lower(?) THEN 0 ELSE 1 END,
                          i.trading_symbol ASC, i.instrument_type NULLS FIRST, i.expiry NULLS FIRST,
                          i.strike NULLS FIRST, i.symbol_token ASC
                 LIMIT ? OFFSET ?
                """, (rs, row) -> instrumentMapper.mapRow(rs, row), pageArgs.toArray());
        long count = total == null ? 0L : total;
        int pages = count == 0 ? 0 : (int) Math.ceil(count / (double) size);
        return new InstrumentSearchResponse(content, page, size, count, pages, page + 1 < pages,
                state.version(), state.activatedAt());
    }

    public CatalogueSummary catalogueSummary() {
        return jdbc.query("""
                SELECT s.active_version, s.activated_at,
                       (SELECT count(*) FROM instrument_master i
                         WHERE i.catalogue_version = s.active_version AND i.active) AS active_count
                  FROM instrument_catalogue_state s WHERE s.singleton_id = 1
                """, (rs, row) -> new CatalogueSummary(
                rs.getObject("active_version", UUID.class),
                toInstant(rs.getObject("activated_at", java.time.OffsetDateTime.class)),
                rs.getLong("active_count")))
                .stream().findFirst().orElse(new CatalogueSummary(null, null, 0));
    }


    private static Instant toInstant(java.time.OffsetDateTime value) {
        return value == null ? null : value.toInstant();
    }

    private static String bounded(String text, int max) {
        if (text == null) return null;
        return text.length() <= max ? text : text.substring(0, max);
    }

    private record CatalogueState(UUID version, Instant activatedAt) {}
    public record CatalogueSummary(UUID version, Instant activatedAt, long activeCount) {}
}
