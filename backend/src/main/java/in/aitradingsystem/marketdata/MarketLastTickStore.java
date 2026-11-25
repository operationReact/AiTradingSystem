package in.aitradingsystem.marketdata;

import in.aitradingsystem.provider.angelone.CustomerAngelOneLiveQuoteService.LiveQuote;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** Stores the last verified LIVE tick per instrument so closed-market reads can show a last price. */
@Repository
public class MarketLastTickStore {
    private static final ZoneId MARKET_ZONE = ZoneId.of("Asia/Kolkata");

    public record LastTick(BigDecimal ltp, BigDecimal previousClose, Instant exchangeAt) {
        /** Percentage change vs the previous stored session close; null until a previous close exists. */
        public BigDecimal changePercent() {
            if (previousClose == null || previousClose.signum() <= 0) return null;
            return ltp.subtract(previousClose).multiply(BigDecimal.valueOf(100))
                .divide(previousClose, 4, java.math.RoundingMode.HALF_UP);
        }
    }

    private final JdbcTemplate jdbc;

    public MarketLastTickStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Records only verified LIVE quotes; a newer session date moves the prior last tick into prev_close. */
    public void recordLive(Collection<LiveQuote> quotes) {
        for (LiveQuote quote : quotes) {
            if (!"LIVE".equals(quote.status()) || quote.ltp() == null || quote.ltp().signum() <= 0
                    || quote.exchangeAt() == null) {
                continue;
            }
            LocalDate session = quote.exchangeAt().atZone(MARKET_ZONE).toLocalDate();
            jdbc.update("""
                    INSERT INTO market_last_tick (instrument_id, session_date, ltp, prev_close, exchange_at, updated_at)
                    VALUES (?, ?, ?, NULL, ?, now())
                    ON CONFLICT (instrument_id) DO UPDATE SET
                        prev_close = CASE WHEN market_last_tick.session_date <> EXCLUDED.session_date
                            THEN market_last_tick.ltp ELSE market_last_tick.prev_close END,
                        session_date = EXCLUDED.session_date,
                        ltp = EXCLUDED.ltp,
                        exchange_at = EXCLUDED.exchange_at,
                        updated_at = now()
                    WHERE market_last_tick.exchange_at <= EXCLUDED.exchange_at
                    """, quote.instrumentId(), session, quote.ltp(), Timestamp.from(quote.exchangeAt()));
        }
    }

    public Map<Long, LastTick> lastFor(Collection<Long> instrumentIds) {
        Map<Long, LastTick> result = new HashMap<>();
        if (instrumentIds.isEmpty()) return result;
        String placeholders = String.join(",", java.util.Collections.nCopies(instrumentIds.size(), "?"));
        jdbc.query("SELECT instrument_id, ltp, prev_close, exchange_at FROM market_last_tick WHERE instrument_id IN ("
                + placeholders + ")", rs -> {
            result.put(rs.getLong("instrument_id"), new LastTick(rs.getBigDecimal("ltp"),
                rs.getBigDecimal("prev_close"), rs.getTimestamp("exchange_at").toInstant()));
        }, instrumentIds.toArray());
        return result;
    }
}
