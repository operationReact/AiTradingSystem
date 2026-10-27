package in.aitradingsystem.strategy;

import java.sql.Types;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** PostgreSQL persistence for idempotent deterministic strategy evaluations. */
@Repository
public class StrategySignalRepository {
    private final JdbcTemplate jdbc;

    public StrategySignalRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void save(StrategySignal signal) {
        jdbc.update("""
                INSERT INTO strategy_signal
                    (id, instrument_id, exchange, trading_symbol, symbol_token, interval, action, strategy,
                     reference_price, stop_loss, target_price, fast_average, slow_average, average_volume,
                     latest_volume, confidence, reason_codes, source, latest_candle_at, created_at, status)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (instrument_id, interval, strategy, latest_candle_at) DO UPDATE SET
                    action = EXCLUDED.action,
                    reference_price = EXCLUDED.reference_price,
                    stop_loss = EXCLUDED.stop_loss,
                    target_price = EXCLUDED.target_price,
                    fast_average = EXCLUDED.fast_average,
                    slow_average = EXCLUDED.slow_average,
                    average_volume = EXCLUDED.average_volume,
                    latest_volume = EXCLUDED.latest_volume,
                    confidence = EXCLUDED.confidence,
                    reason_codes = EXCLUDED.reason_codes,
                    source = EXCLUDED.source,
                    created_at = EXCLUDED.created_at,
                    status = EXCLUDED.status
                """,
                UUID.fromString(signal.id()), signal.instrumentId(), signal.exchange(), signal.symbol(),
                "", signal.interval(), signal.action(), signal.strategy(), signal.referencePrice(),
                signal.stopLoss(), signal.target(), signal.fastAverage(), signal.slowAverage(),
                signal.averageVolume(), signal.latestVolume(), signal.confidence(),
                String.join(",", signal.reasonCodes()), signal.source(),
                timestamp(signal.latestCandleAt()), timestamp(signal.createdAt()), signal.status());
    }

    public List<StrategySignal> recent(int limit) {
        if (limit < 1 || limit > 100) throw new IllegalArgumentException("limit must be between 1 and 100");
        return jdbc.query("""
                SELECT id, instrument_id, trading_symbol, exchange, interval, action, strategy,
                       reference_price, stop_loss, target_price, fast_average, slow_average,
                       average_volume, latest_volume, confidence, reason_codes, source,
                       created_at, latest_candle_at, status
                  FROM strategy_signal
                 ORDER BY created_at DESC, id DESC
                 LIMIT ?
                """, (rs, row) -> new StrategySignal(
                    rs.getObject("id", UUID.class).toString(),
                    rs.getLong("instrument_id"),
                    rs.getString("trading_symbol"),
                    rs.getString("exchange"),
                    rs.getString("interval"),
                    rs.getString("action"),
                    rs.getString("strategy"),
                    rs.getBigDecimal("reference_price"),
                    rs.getBigDecimal("stop_loss"),
                    rs.getBigDecimal("target_price"),
                    rs.getBigDecimal("fast_average"),
                    rs.getBigDecimal("slow_average"),
                    rs.getBigDecimal("average_volume"),
                    rs.getBigDecimal("latest_volume"),
                    rs.getBigDecimal("confidence").doubleValue(),
                    Arrays.stream(rs.getString("reason_codes").split(",")).filter(s -> !s.isBlank()).toList(),
                    rs.getString("source"),
                    instant(rs.getObject("created_at", OffsetDateTime.class)),
                    instant(rs.getObject("latest_candle_at", OffsetDateTime.class)),
                    rs.getString("status")), limit);
    }

    private static OffsetDateTime timestamp(Instant value) {
        return OffsetDateTime.ofInstant(value, ZoneOffset.UTC);
    }

    private static Instant instant(OffsetDateTime value) {
        return value == null ? null : value.toInstant();
    }
}
