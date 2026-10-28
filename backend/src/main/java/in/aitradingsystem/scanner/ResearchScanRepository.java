package in.aitradingsystem.scanner;

import in.aitradingsystem.instrument.Instrument;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

@Repository
public class ResearchScanRepository {
    private final JdbcTemplate jdbc;

    private final RowMapper<Instrument> instrumentMapper = (rs, row) -> new Instrument(
            rs.getLong("instrument_id"), rs.getString("exchange_segment"), rs.getString("exchange"),
            rs.getString("symbol_token"), rs.getString("trading_symbol"), rs.getString("instrument_name"),
            rs.getString("instrument_type"), rs.getObject("expiry", java.time.LocalDate.class),
            rs.getBigDecimal("strike"), rs.getInt("lot_size"), rs.getBigDecimal("tick_size"),
            rs.getObject("catalogue_version", UUID.class));

    public ResearchScanRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void createRun(UUID id, String interval, int requestedLimit, String strategySet) {
        jdbc.update("""
                INSERT INTO research_scan_run (id, interval, status, requested_limit, strategy_set)
                VALUES (?, ?, 'RUNNING', ?, ?)
                """, id, interval, requestedLimit, strategySet);
    }

    /** Bounded active cash-equity universe only; excludes derivatives and inactive catalogue rows. */
    public List<Instrument> activeCashEquityUniverse(int limit) {
        return jdbc.query("""
                SELECT i.instrument_id, i.exchange_segment, i.exchange, i.symbol_token, i.trading_symbol,
                       i.instrument_name, i.instrument_type, i.expiry, i.strike, i.lot_size, i.tick_size,
                       i.catalogue_version
                  FROM instrument_master i
                  JOIN instrument_catalogue_state s
                    ON s.singleton_id = 1 AND s.active_version = i.catalogue_version
                 WHERE i.active = TRUE
                   AND i.exchange IN ('NSE', 'BSE')
                   AND upper(coalesce(i.instrument_type, '')) = 'EQ'
                 ORDER BY i.exchange ASC, i.trading_symbol ASC, i.symbol_token ASC
                 LIMIT ?
                """, instrumentMapper, limit);
    }

    public void saveCandidate(UUID runId, ScanCandidate c) {
        jdbc.update("""
                INSERT INTO research_scan_candidate
                    (scan_run_id, instrument_id, trading_symbol, exchange, interval, strategy, score,
                     entry_price, stop_price, target_price, signal_candle_time, reasons)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (scan_run_id, instrument_id, strategy, signal_candle_time) DO NOTHING
                """, runId, c.instrumentId(), c.tradingSymbol(), c.exchange(), c.interval(), c.strategy(),
                c.score(), c.referencePrice(), c.suggestedStop(), c.suggestedTarget(), offset(c.candleTime()),
                String.join("|", c.reasons()));
    }

    public List<ResearchScanCandidateView> rankedCandidates(UUID runId, int limit) {
        requireRun(runId);
        if (limit < 1 || limit > 200) throw new IllegalArgumentException("limit must be between 1 and 200");
        return jdbc.query("""
                SELECT scan_run_id, instrument_id, trading_symbol, exchange, interval, strategy, score,
                       entry_price, stop_price, target_price, signal_candle_time, reasons
                  FROM research_scan_candidate
                 WHERE scan_run_id = ?
                 ORDER BY score DESC, trading_symbol ASC, strategy ASC, signal_candle_time DESC
                 LIMIT ?
                """, (rs, row) -> new ResearchScanCandidateView(
                rs.getObject("scan_run_id", UUID.class), rs.getLong("instrument_id"), rs.getString("trading_symbol"),
                rs.getString("exchange"), rs.getString("interval"), rs.getString("strategy"), rs.getDouble("score"),
                rs.getBigDecimal("entry_price"), rs.getBigDecimal("stop_price"), rs.getBigDecimal("target_price"),
                instant(rs.getObject("signal_candle_time", OffsetDateTime.class)), splitReasons(rs.getString("reasons"))),
                runId, limit);
    }

    public void finishRun(UUID id, int scannedCount, int candidateCount) {
        jdbc.update("""
                UPDATE research_scan_run
                   SET status = 'SUCCEEDED', scanned_count = ?, candidate_count = ?, completed_at = now(),
                       error_code = NULL, error_message = NULL
                 WHERE id = ? AND status = 'RUNNING'
                """, scannedCount, candidateCount, id);
    }

    public void failRun(UUID id, int scannedCount, int candidateCount, String code, String message) {
        jdbc.update("""
                UPDATE research_scan_run
                   SET status = 'FAILED', scanned_count = ?, candidate_count = ?, completed_at = now(),
                       error_code = ?, error_message = ?
                 WHERE id = ? AND status = 'RUNNING'
                """, scannedCount, candidateCount, bounded(code, 80), bounded(message, 500), id);
    }

    public ResearchScanSummary requireRun(UUID id) {
        return jdbc.query("SELECT * FROM research_scan_run WHERE id = ?", (rs, row) -> new ResearchScanSummary(
                rs.getObject("id", UUID.class), rs.getString("interval"), rs.getString("status"),
                rs.getInt("requested_limit"), rs.getInt("scanned_count"), rs.getInt("candidate_count"),
                rs.getString("strategy_set"), instant(rs.getObject("started_at", OffsetDateTime.class)),
                instant(rs.getObject("completed_at", OffsetDateTime.class)), rs.getString("error_code"),
                rs.getString("error_message")), id).stream().findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Scan run was not found"));
    }

    private static List<String> splitReasons(String reasons) {
        if (reasons == null || reasons.isBlank()) return List.of();
        return Arrays.stream(reasons.split("\\|"))
                .filter(value -> !value.isBlank())
                .toList();
    }

    private static OffsetDateTime offset(Instant value) {
        return OffsetDateTime.ofInstant(value, ZoneOffset.UTC);
    }

    private static Instant instant(OffsetDateTime value) {
        return value == null ? null : value.toInstant();
    }

    private static String bounded(String value, int max) {
        if (value == null) return null;
        String clean = value.replaceAll("[\\r\\n\\t]", " ").trim();
        return clean.length() <= max ? clean : clean.substring(0, max);
    }
}
