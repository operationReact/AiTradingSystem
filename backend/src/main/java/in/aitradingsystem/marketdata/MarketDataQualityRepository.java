package in.aitradingsystem.marketdata;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class MarketDataQualityRepository {
    private final JdbcTemplate jdbc;

    public MarketDataQualityRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public List<Instant> candleTimes(long instrumentId, HistoricalCandleInterval interval, Instant from, Instant to) {
        return jdbc.query("""
            SELECT candle_time FROM market_candle
             WHERE instrument_id = ? AND interval = ? AND candle_time >= ? AND candle_time < ?
             ORDER BY candle_time
            """, (rs, row) -> rs.getObject(1, OffsetDateTime.class).toInstant(),
                instrumentId, interval.name(), OffsetDateTime.ofInstant(from, ZoneOffset.UTC),
                OffsetDateTime.ofInstant(to, ZoneOffset.UTC));
    }

    public int zeroVolumeCount(long instrumentId, HistoricalCandleInterval interval, Instant from, Instant to) {
        Integer count = jdbc.queryForObject("""
            SELECT COUNT(*) FROM market_candle
             WHERE instrument_id = ? AND interval = ? AND candle_time >= ? AND candle_time < ? AND volume = 0
            """, Integer.class, instrumentId, interval.name(), OffsetDateTime.ofInstant(from, ZoneOffset.UTC),
                OffsetDateTime.ofInstant(to, ZoneOffset.UTC));
        return count == null ? 0 : count;
    }

    public void upsertQualityResult(MarketDataQualityResult result, String gapRangesJson) {
        jdbc.update("""
            INSERT INTO market_data_quality
              (instrument_id, interval, window_from, window_to, expected_candles, actual_candles,
               missing_candles, duplicate_candles, zero_volume_candles, status, gap_ranges, evaluated_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, CAST(? AS jsonb), now())
            ON CONFLICT (instrument_id, interval, window_from, window_to) DO UPDATE SET
              expected_candles = EXCLUDED.expected_candles, actual_candles = EXCLUDED.actual_candles,
              missing_candles = EXCLUDED.missing_candles, duplicate_candles = EXCLUDED.duplicate_candles,
              zero_volume_candles = EXCLUDED.zero_volume_candles, status = EXCLUDED.status,
              gap_ranges = EXCLUDED.gap_ranges, evaluated_at = EXCLUDED.evaluated_at
            """, result.instrumentId(), result.interval().name(),
                OffsetDateTime.ofInstant(result.from(), ZoneOffset.UTC),
                OffsetDateTime.ofInstant(result.to(), ZoneOffset.UTC),
                result.expectedCandles(), result.actualCandles(), result.missingCandles(),
                result.duplicateCandles(), result.zeroVolumeCandles(), result.status(), gapRangesJson);
    }

    public String findLatest(long instrumentId, HistoricalCandleInterval interval) {
        return jdbc.query("""
            SELECT status FROM market_data_quality WHERE instrument_id = ? AND interval = ?
             ORDER BY evaluated_at DESC LIMIT 1
            """, rs -> rs.next() ? rs.getString(1) : null, instrumentId, interval.name());
    }

    public List<Long> findDegraded(int limit) {
        return jdbc.query("""
            SELECT DISTINCT instrument_id FROM market_data_quality
             WHERE status <> 'HEALTHY' ORDER BY instrument_id LIMIT ?
            """, (rs, row) -> rs.getLong(1), limit);
    }
}
