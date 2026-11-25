package in.aitradingsystem.marketdata;

import java.time.Instant;
import java.time.Duration;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.math.BigDecimal;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/**
 * Strictly owner-private source archive; never writes to or reads from market_candle.
 * Only OwnerPrivateIntradayController is permitted to return these persisted rows.
 */
@Repository
public class OwnerPrivateIntradayRepository {
    private final JdbcTemplate jdbc;

    public OwnerPrivateIntradayRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Atomic per-token 12-hour claim across restarted workers and replicas. */
    public boolean claim(String owner, String token, String interval, Instant now) {
        return claim(owner, token, interval, now, Duration.ofHours(12));
    }

    /**
     * One owner-private claim per instrument/interval and bounded cooldown.
     * A session-aware worker can recheck actual provider bars after a new
     * interval closes; a concurrent replica cannot race the same claim.
     */
    public boolean claim(String owner, String token, String interval, Instant now,
                         Duration minimumSpacing) {
        check(owner, token, interval);
        if (now == null || minimumSpacing == null ||
                minimumSpacing.compareTo(Duration.ofMinutes(5)) < 0 ||
                minimumSpacing.compareTo(Duration.ofHours(12)) > 0) {
            throw new IllegalArgumentException("Invalid intraday query cooldown");
        }
        return jdbc.query("""
                INSERT INTO owner_private_intraday_cursor
                  (owner_subject,exchange,symbol_token,interval,attempted_at,status)
                VALUES (?,'NSE',?,?,?,'RUNNING')
                ON CONFLICT(owner_subject,exchange,symbol_token,interval)
                DO UPDATE SET attempted_at=EXCLUDED.attempted_at,
                              status='RUNNING',received_rows=0,error_code=NULL
                 WHERE owner_private_intraday_cursor.attempted_at < ?
                RETURNING symbol_token
                """, (rs, i) -> rs.getString(1),
                owner, token, interval, utc(now), utc(now.minus(minimumSpacing))).size() == 1;
    }

    @Transactional
    public void save(String owner, String token, String symbol, String interval,
                     LocalDate through, Instant at,
                     List<HistoricalCandleParser.CandleValues> values) {
        check(owner, token, interval);
        if (symbol == null || !symbol.matches("[A-Z0-9&._-]{1,100}")
                || values == null || values.size() > 2000) {
            throw new IllegalArgumentException("Invalid owner intraday rows");
        }
        for (var candle : values) {
            jdbc.update("""
                    INSERT INTO owner_private_intraday_candle
                      (owner_subject,exchange,symbol_token,trading_symbol,interval,candle_time,
                       open_price,high_price,low_price,close_price,volume,source,fetched_at)
                    VALUES (?,'NSE',?,?,?,?,?,?,?,?,?,'ANGEL_ONE_OWNER_PRIVATE',?)
                    ON CONFLICT(owner_subject,exchange,symbol_token,interval,candle_time)
                    DO UPDATE SET open_price=EXCLUDED.open_price,high_price=EXCLUDED.high_price,
                      low_price=EXCLUDED.low_price,close_price=EXCLUDED.close_price,
                      volume=EXCLUDED.volume,fetched_at=EXCLUDED.fetched_at
                    """, owner,token,symbol,interval,utc(candle.candleTime()),
                    candle.open(),candle.high(),candle.low(),candle.close(),
                    candle.volume(),utc(at));
        }
        jdbc.update("""
                UPDATE owner_private_intraday_cursor
                   SET status=?,completed_through=?,received_rows=?,error_code=NULL
                 WHERE owner_subject=? AND exchange='NSE' AND symbol_token=? AND interval=?
                """, values.isEmpty() ? "EMPTY" : "SUCCEEDED",
                through, values.size(), owner,token,interval);
    }

    public void failed(String owner, String token, String interval) {
        check(owner,token,interval);
        jdbc.update("""
                UPDATE owner_private_intraday_cursor
                   SET status='FAILED',error_code='PROVIDER_UNAVAILABLE'
                 WHERE owner_subject=? AND exchange='NSE' AND symbol_token=? AND interval=?
                """,owner,token,interval);
    }

    public List<CustomerMarketCandleController.CustomerCandle> candles(
            String owner, String token, String interval, Instant from, Instant until) {
        check(owner,token,interval);
        return jdbc.query("""
                SELECT candle_time,open_price,high_price,low_price,close_price,
                       volume,fetched_at
                  FROM owner_private_intraday_candle
                 WHERE owner_subject=? AND exchange='NSE' AND symbol_token=?
                   AND interval=? AND candle_time>=? AND candle_time<?
                 ORDER BY candle_time ASC LIMIT 500
                """, (rs,i)-> new CustomerMarketCandleController.CustomerCandle(
                    rs.getObject("candle_time",OffsetDateTime.class).toInstant(),
                    rs.getBigDecimal("open_price"),rs.getBigDecimal("high_price"),
                    rs.getBigDecimal("low_price"),rs.getBigDecimal("close_price"),
                    rs.getLong("volume"),"ANGEL_ONE_OWNER_PRIVATE",
                    rs.getObject("fetched_at",OffsetDateTime.class).toInstant()),
                owner,token,interval,utc(from),utc(until));
    }

    private static OffsetDateTime utc(Instant instant) {
        return OffsetDateTime.ofInstant(instant,ZoneOffset.UTC);
    }

    private static void check(String owner, String token, String interval) {
        if (owner == null || owner.isBlank() || owner.length() > 200
                || token == null || !token.matches("[0-9]{1,12}")
                || !("FIVE_MINUTE".equals(interval)||"ONE_HOUR".equals(interval))) {
            throw new IllegalArgumentException("Invalid owner-private research identity");
        }
    }
}
