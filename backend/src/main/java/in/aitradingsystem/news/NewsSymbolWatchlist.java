package in.aitradingsystem.news;

import java.time.Instant;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/** Current NSE cash-equity symbols from the active instrument catalogue, cached for 15 minutes. */
@Component
public class NewsSymbolWatchlist {
    private static final Logger log = LoggerFactory.getLogger(NewsSymbolWatchlist.class);
    private static final long CACHE_SECONDS = 900;
    private final JdbcClient jdbc;
    private volatile Snapshot cached;

    public NewsSymbolWatchlist(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public List<String> current(List<String> configuredFallback) {
        Instant now = Instant.now();
        Snapshot snapshot = cached;
        if (snapshot != null && snapshot.loadedAt().plusSeconds(CACHE_SECONDS).isAfter(now)) {
            return snapshot.symbols();
        }
        synchronized (this) {
            snapshot = cached;
            if (snapshot != null && snapshot.loadedAt().plusSeconds(CACHE_SECONDS).isAfter(now)) {
                return snapshot.symbols();
            }
            try {
                List<String> symbols = jdbc.sql("""
                    SELECT DISTINCT upper(regexp_replace(i.trading_symbol, '-EQ$', ''))
                      FROM instrument_master i
                      JOIN instrument_catalogue_state s
                        ON s.singleton_id = 1 AND s.active_version = i.catalogue_version
                     WHERE i.active = TRUE AND i.exchange = 'NSE'
                       AND i.exchange_segment = 'NSE_CM'
                       AND i.trading_symbol LIKE '%-EQ'
                       AND upper(i.trading_symbol) NOT LIKE '%NSETEST%'
                     ORDER BY 1
                     LIMIT 5000
                    """).query(String.class).list();
                if (!symbols.isEmpty()) {
                    cached = new Snapshot(now, List.copyOf(symbols));
                    return cached.symbols();
                }
            } catch (DataAccessException e) {
                log.warn("Active NSE symbol catalogue unavailable; using configured news-symbol fallback");
            }
            List<String> fallback = configuredFallback == null ? List.of()
                : configuredFallback.stream().filter(s -> s != null && s.matches("[A-Z0-9&-]{1,20}"))
                    .distinct().sorted().toList();
            cached = new Snapshot(now, fallback);
            log.warn("No active NSE equity symbols found; news symbol coverage is limited to {} configured entries",
                fallback.size());
            return fallback;
        }
    }

    private record Snapshot(Instant loadedAt, List<String> symbols) {}
}
