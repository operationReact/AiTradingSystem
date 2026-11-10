package in.aitradingsystem.marketdata;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Read-only coverage of the active NSE/BSE EQ catalogue. This does not claim
 * that 64 stored bars are quality-valid, fresh enough for trading, or licensed
 * for redistribution; it only measures the verified historical store.
 *
 * The lateral window uses the existing (exchange, symbol_token, interval,
 * candle_time DESC) index and never loads a full-market candle set into Java.
 */
@Repository
public class UniverseCandleCoverageRepository {
    public static final int REQUIRED_BARS = 64;

    private static final String EQUITIES_WITH_BARS = """
            FROM instrument_master i
            JOIN instrument_catalogue_state s
              ON s.singleton_id = 1 AND s.active_version = i.catalogue_version
            CROSS JOIN LATERAL (
                SELECT COUNT(*) AS bar_count FROM (
                    SELECT m.candle_time
                      FROM market_candle m
                     WHERE m.exchange = i.exchange
                       AND m.symbol_token = i.symbol_token
                       AND m.interval = 'FIVE_MINUTE'
                       AND m.candle_time >= ?
                       AND m.candle_time < ?
                     ORDER BY m.candle_time DESC
                     LIMIT 64
                ) recent
            ) bars
            WHERE i.active = TRUE
              AND i.exchange IN ('NSE', 'BSE')
              AND upper(coalesce(i.instrument_type, '')) = 'EQ'
            """;

    private final JdbcTemplate jdbc;

    public UniverseCandleCoverageRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public record Coverage(Instant fromInclusive, Instant toExclusive,
                           long activeEquities, long with64Bars,
                           long under64Bars, long withNoBars) {}

    public Coverage coverage(Instant fromInclusive, Instant toExclusive) {
        validateWindow(fromInclusive, toExclusive);
        return jdbc.query("""
                SELECT COUNT(*) AS eligible,
                       COUNT(*) FILTER (WHERE bars.bar_count >= 64) AS covered,
                       COUNT(*) FILTER (WHERE bars.bar_count < 64) AS incomplete,
                       COUNT(*) FILTER (WHERE bars.bar_count = 0) AS empty
                """ + EQUITIES_WITH_BARS,
                rs -> {
                    if (!rs.next()) throw new IllegalStateException("Coverage aggregation missing");
                    return new Coverage(fromInclusive, toExclusive,
                            rs.getLong("eligible"), rs.getLong("covered"),
                            rs.getLong("incomplete"), rs.getLong("empty"));
                }, utc(fromInclusive), utc(toExclusive));
    }

    /**
     * Select one under-covered equity not attempted during the cooldown.
     * Failed and zero-row runs both cool down, avoiding a tight retry loop
     * for suspended / illiquid tokens and provider outages.
     */
    public Optional<Long> nextDue(Instant fromInclusive, Instant toExclusive,
                                  Instant attemptedSince) {
        validateWindow(fromInclusive, toExclusive);
        if (attemptedSince == null) throw new IllegalArgumentException("Cooldown cutoff is required");
        return jdbc.query("""
                SELECT i.instrument_id
                """ + EQUITIES_WITH_BARS + """
                  AND bars.bar_count < 64
                  AND NOT EXISTS (
                      SELECT 1
                        FROM candle_backfill_run r
                       WHERE r.exchange = i.exchange
                         AND r.symbol_token = i.symbol_token
                         AND r.interval = 'FIVE_MINUTE'
                         AND r.started_at >= ?
                  )
                 ORDER BY bars.bar_count ASC,
                          i.exchange ASC, i.trading_symbol ASC, i.symbol_token ASC
                 LIMIT 1
                """, (rs, row) -> rs.getLong("instrument_id"),
                utc(fromInclusive), utc(toExclusive), utc(attemptedSince))
                .stream().findFirst();
    }

    private static void validateWindow(Instant from, Instant to) {
        if (from == null || to == null || !from.isBefore(to)) {
            throw new IllegalArgumentException("A bounded coverage window is required");
        }
    }

    private static OffsetDateTime utc(Instant time) {
        return OffsetDateTime.ofInstant(time, ZoneOffset.UTC);
    }
}
