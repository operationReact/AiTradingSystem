package in.aitradingsystem.marketdata;

import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Types;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Repository
public class MarketDataRepository {
    private static final int BATCH_SIZE = 1_000;
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transaction;
    private final RowMapper<MarketCandle> candleMapper = (rs, row) -> new MarketCandle(
            rs.getLong("candle_id"), rs.getLong("instrument_id"), rs.getString("exchange"),
            rs.getString("trading_symbol"), rs.getString("symbol_token"),
            rs.getObject("catalogue_version", UUID.class), rs.getString("interval"),
            instant(rs.getObject("candle_time", OffsetDateTime.class)), rs.getBigDecimal("open_price"),
            rs.getBigDecimal("high_price"), rs.getBigDecimal("low_price"), rs.getBigDecimal("close_price"),
            rs.getLong("volume"), rs.getString("source"),
            instant(rs.getObject("fetched_at", OffsetDateTime.class)));
    private final RowMapper<CandleBackfillRun> runMapper = (rs, row) -> new CandleBackfillRun(
            rs.getObject("id", UUID.class), rs.getLong("instrument_id"), rs.getString("exchange"),
            rs.getString("trading_symbol"), rs.getString("symbol_token"),
            rs.getObject("catalogue_version", UUID.class), rs.getString("interval"),
            rs.getObject("from_date", LocalDate.class), rs.getObject("to_date", LocalDate.class),
            rs.getObject("next_from_date", LocalDate.class), rs.getString("status"),
            rs.getInt("chunks_completed"), rs.getLong("fetched_rows"), rs.getLong("persisted_rows"),
            instant(rs.getObject("started_at", OffsetDateTime.class)),
            instant(rs.getObject("updated_at", OffsetDateTime.class)),
            instant(rs.getObject("completed_at", OffsetDateTime.class)),
            rs.getString("error_code"), rs.getString("error_message"));

    public MarketDataRepository(JdbcTemplate jdbc, PlatformTransactionManager transactionManager) {
        this.jdbc = jdbc;
        this.transaction = new TransactionTemplate(transactionManager);
    }

    public void recoverInterruptedRuns() {
        jdbc.update("""
                UPDATE candle_backfill_run
                   SET status = CASE WHEN chunks_completed > 0 THEN 'PARTIAL' ELSE 'FAILED' END,
                       updated_at = now(), completed_at = NULL,
                       error_code = 'INTERRUPTED',
                       error_message = 'The service restarted before this backfill completed'
                 WHERE status = 'RUNNING'
                """);
    }

    public CandleBackfillRun createRun(UUID id, long instrumentId, String exchange, String tradingSymbol,
                                       String symbolToken, UUID catalogueVersion, String interval,
                                       LocalDate fromDate, LocalDate toDate) {
        jdbc.update("""
                INSERT INTO candle_backfill_run
                    (id, instrument_id, exchange, trading_symbol, symbol_token, catalogue_version, interval,
                     from_date, to_date, next_from_date, status)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'RUNNING')
                """, id, instrumentId, exchange, tradingSymbol, symbolToken, catalogueVersion,
                interval, fromDate, toDate, fromDate);
        return requireRun(id);
    }

    public CandleBackfillRun claimForResume(UUID id) {
        int updated = jdbc.update("""
                UPDATE candle_backfill_run
                   SET status = 'RUNNING', updated_at = now(), completed_at = NULL,
                       error_code = NULL, error_message = NULL
                 WHERE id = ? AND status IN ('PARTIAL','FAILED')
                """, id);
        if (updated == 1) return requireRun(id);
        CandleBackfillRun run = findRun(id);
        if (run == null) throw new MarketDataException("BACKFILL_NOT_FOUND", "Backfill run was not found");
        if ("RUNNING".equals(run.status())) {
            throw new MarketDataException("BACKFILL_ALREADY_RUNNING", "This backfill is already running");
        }
        throw new MarketDataException("BACKFILL_NOT_RESUMABLE", "Only partial or failed backfills can be resumed");
    }

    public void persistChunk(UUID runId, LocalDate chunkFrom, LocalDate chunkTo, LocalDate nextFrom,
                             List<MarketCandle> candles) {
        transaction.executeWithoutResult(status -> {
            for (int start = 0; start < candles.size(); start += BATCH_SIZE) {
                List<MarketCandle> batch = candles.subList(start, Math.min(candles.size(), start + BATCH_SIZE));
                jdbc.batchUpdate("""
                        INSERT INTO market_candle
                            (instrument_id, exchange, trading_symbol, symbol_token, catalogue_version, interval,
                             candle_time, open_price, high_price, low_price, close_price, volume, source, fetched_at)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                        ON CONFLICT (instrument_id, interval, candle_time) DO UPDATE SET
                            exchange = EXCLUDED.exchange,
                            trading_symbol = EXCLUDED.trading_symbol,
                            symbol_token = EXCLUDED.symbol_token,
                            catalogue_version = EXCLUDED.catalogue_version,
                            open_price = EXCLUDED.open_price,
                            high_price = EXCLUDED.high_price,
                            low_price = EXCLUDED.low_price,
                            close_price = EXCLUDED.close_price,
                            volume = EXCLUDED.volume,
                            source = EXCLUDED.source,
                            fetched_at = EXCLUDED.fetched_at
                        """, batch, batch.size(), (PreparedStatement ps, MarketCandle candle) -> bindCandle(ps, candle));
            }
            int count = candles.size();
            jdbc.update("""
                    INSERT INTO candle_backfill_checkpoint
                        (run_id, chunk_from_date, chunk_to_date, status, fetched_rows, persisted_rows)
                    VALUES (?, ?, ?, 'SUCCEEDED', ?, ?)
                    """, runId, chunkFrom, chunkTo, count, count);
            int advanced = jdbc.update("""
                    UPDATE candle_backfill_run
                       SET next_from_date = ?, chunks_completed = chunks_completed + 1,
                           fetched_rows = fetched_rows + ?, persisted_rows = persisted_rows + ?,
                           updated_at = now(), error_code = NULL, error_message = NULL
                     WHERE id = ? AND status = 'RUNNING' AND next_from_date = ?
                    """, nextFrom, count, count, runId, chunkFrom);
            if (advanced != 1) throw new MarketDataException("BACKFILL_CHECKPOINT_CONFLICT",
                    "Backfill checkpoint changed while a chunk was being saved");
        });
    }

    private void bindCandle(PreparedStatement ps, MarketCandle candle) throws SQLException {
        ps.setLong(1, candle.instrumentId());
        ps.setString(2, candle.exchange());
        ps.setString(3, candle.tradingSymbol());
        ps.setString(4, candle.symbolToken());
        if (candle.catalogueVersion() == null) ps.setNull(5, Types.OTHER); else ps.setObject(5, candle.catalogueVersion());
        ps.setString(6, candle.interval());
        ps.setObject(7, OffsetDateTime.ofInstant(candle.candleTime(), ZoneOffset.UTC));
        ps.setBigDecimal(8, candle.open());
        ps.setBigDecimal(9, candle.high());
        ps.setBigDecimal(10, candle.low());
        ps.setBigDecimal(11, candle.close());
        ps.setLong(12, candle.volume());
        ps.setString(13, candle.source());
        ps.setObject(14, OffsetDateTime.ofInstant(candle.fetchedAt(), ZoneOffset.UTC));
    }

    public void recordChunkFailure(UUID runId, LocalDate chunkFrom, LocalDate chunkTo,
                                   String code, String message) {
        transaction.executeWithoutResult(status -> {
            jdbc.update("""
                    INSERT INTO candle_backfill_checkpoint
                        (run_id, chunk_from_date, chunk_to_date, status, error_code, error_message)
                    VALUES (?, ?, ?, 'FAILED', ?, ?)
                    """, runId, chunkFrom, chunkTo, bounded(code, 80), bounded(message, 500));
            jdbc.update("""
                    UPDATE candle_backfill_run
                       SET status = CASE WHEN chunks_completed > 0 THEN 'PARTIAL' ELSE 'FAILED' END,
                           updated_at = now(), completed_at = NULL,
                           error_code = ?, error_message = ?
                     WHERE id = ? AND status = 'RUNNING'
                    """, bounded(code, 80), bounded(message, 500), runId);
        });
    }

    public CandleBackfillRun finishRun(UUID id, boolean complete) {
        jdbc.update("""
                UPDATE candle_backfill_run
                   SET status = ?, updated_at = now(),
                       completed_at = CASE WHEN ? THEN now() ELSE NULL END,
                       error_code = CASE WHEN ? THEN NULL ELSE error_code END,
                       error_message = CASE WHEN ? THEN NULL ELSE error_message END
                 WHERE id = ? AND status = 'RUNNING'
                """, complete ? "SUCCEEDED" : "PARTIAL", complete, complete, complete, id);
        return requireRun(id);
    }

    public CandleBackfillRun requireRun(UUID id) {
        CandleBackfillRun run = findRun(id);
        if (run == null) throw new MarketDataException("BACKFILL_NOT_FOUND", "Backfill run was not found");
        return run;
    }

    private CandleBackfillRun findRun(UUID id) {
        return jdbc.query("SELECT * FROM candle_backfill_run WHERE id = ?", runMapper, id)
                .stream().findFirst().orElse(null);
    }

    public List<CandleBackfillRun> recentRuns(int limit) {
        return jdbc.query("SELECT * FROM candle_backfill_run ORDER BY started_at DESC, id DESC LIMIT ?",
                runMapper, limit);
    }

    public CandlePage candles(long instrumentId, String interval, Instant fromInclusive, Instant toExclusive,
                              int page, int size) {
        Long totalValue = jdbc.queryForObject("""
                SELECT count(*) FROM market_candle
                 WHERE instrument_id = ? AND interval = ? AND candle_time >= ? AND candle_time < ?
                """, Long.class, instrumentId, interval, offset(fromInclusive), offset(toExclusive));
        long total = totalValue == null ? 0 : totalValue;
        List<MarketCandle> rows = jdbc.query("""
                SELECT * FROM market_candle
                 WHERE instrument_id = ? AND interval = ? AND candle_time >= ? AND candle_time < ?
                 ORDER BY candle_time ASC
                 LIMIT ? OFFSET ?
                """, candleMapper, instrumentId, interval, offset(fromInclusive), offset(toExclusive), size,
                (long) page * size);
        int totalPages = total == 0 ? 0 : (int) Math.ceil(total / (double) size);
        return new CandlePage(rows, page, size, total, totalPages, page + 1 < totalPages);
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
