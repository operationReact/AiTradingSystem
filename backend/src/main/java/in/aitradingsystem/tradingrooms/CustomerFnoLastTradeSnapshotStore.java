package in.aitradingsystem.tradingrooms;

import in.aitradingsystem.provider.upstox.UpstoxFnoV3LiveFeedService;
import java.math.BigDecimal;
import java.sql.Date;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Retains only provider-observed Upstox V3 last trades, never executable quotes.
 * Disabled until independent, explicit retention/display rights are verified.
 * Reuses the existing owner-authorized V3 feed and never starts a connection.
 */
@Component
public final class CustomerFnoLastTradeSnapshotStore {
    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    private static final String UPSERT = """
        INSERT INTO customer_fno_verified_display_snapshot
            (instrument_key, exchange_at, observed_at, ltp, previous_close,
             open_interest, retain_until)
        VALUES (?, ?, ?, ?, ?, ?, ?)
        ON CONFLICT (instrument_key) DO UPDATE SET
            exchange_at = EXCLUDED.exchange_at,
            observed_at = EXCLUDED.observed_at,
            ltp = EXCLUDED.ltp,
            previous_close = EXCLUDED.previous_close,
            open_interest = EXCLUDED.open_interest,
            retain_until = EXCLUDED.retain_until
        WHERE customer_fno_verified_display_snapshot.exchange_at <
              EXCLUDED.exchange_at
        """;

    public record LastTrade(BigDecimal ltp, BigDecimal previousClose,
            Long openInterest, Instant exchangeAt, Instant observedAt) {}

    private final JdbcTemplate jdbc;
    private final ObjectProvider<UpstoxFnoV3LiveFeedService> feed;
    private final boolean allowed;
    private final Clock clock;

    @org.springframework.beans.factory.annotation.Autowired
    public CustomerFnoLastTradeSnapshotStore(JdbcTemplate jdbc,
            ObjectProvider<UpstoxFnoV3LiveFeedService> feed, Environment env) {
        this(jdbc, feed, env.getProperty(
                "minttick.upstox-fno.display-rights-verified",
                Boolean.class, false)
            && env.getProperty("minttick.upstox-fno.retained-display-rights-verified",
                Boolean.class, false), Clock.systemUTC());
    }

    CustomerFnoLastTradeSnapshotStore(JdbcTemplate jdbc,
            ObjectProvider<UpstoxFnoV3LiveFeedService> feed,
            boolean allowed, Clock clock) {
        this.jdbc = jdbc;
        this.feed = feed;
        this.allowed = allowed;
        this.clock = clock;
    }

    @Scheduled(cron = "*/30 * * * * *", zone = "Asia/Kolkata")
    public void captureFreshTrades() {
        if (!allowed) return;
        var stream = feed.getIfAvailable();
        if (stream == null) return;
        Instant now = clock.instant();
        LocalDate session = now.atZone(IST).toLocalDate();
        for (var quote : stream.eligibleDisplaySnapshot()) {
            // Verify exchange session before persistence and never store future,
            // stale, zero-priced or out-of-session observations.
            if (quote == null || quote.ltp() == null ||
                    quote.ltp().signum() <= 0 ||
                    quote.exchangeAt() == null || quote.observedAt() == null ||
                    !quote.exchangeAt().atZone(IST).toLocalDate().equals(session) ||
                    quote.observedAt().isBefore(quote.exchangeAt()) ||
                    quote.exchangeAt().isAfter(now) ||
                    quote.exchangeAt().isBefore(now.minusSeconds(15))) continue;
            jdbc.update(UPSERT, quote.instrumentKey(),
                Timestamp.from(quote.exchangeAt()),
                Timestamp.from(quote.observedAt()), quote.ltp(),
                quote.previousClose(), quote.openInterest(),
                Date.valueOf(session.plusDays(7)));
        }
    }

    /** One bounded query for up to 512 exact listed CE/PE keys per discovery refresh. */
    public java.util.Map<String, LastTrade> latestAll(
            java.util.Collection<String> instrumentKeys) {
        if (!allowed || instrumentKeys == null || instrumentKeys.isEmpty()
                || instrumentKeys.size() > 512) return java.util.Map.of();
        var keys = instrumentKeys.stream()
            .filter(k -> k != null && k.matches(
                "(NSE_FO|BSE_FO)\\\\|[A-Za-z0-9._-]{1,64}"))
            .distinct().toList();
        if (keys.isEmpty()) return java.util.Map.of();
        Instant now = clock.instant();
        var parameters = new org.springframework.jdbc.core.namedparam.MapSqlParameterSource()
            .addValue("keys", keys)
            .addValue("today", Date.valueOf(now.atZone(IST).toLocalDate()))
            .addValue("oldest", Timestamp.from(now.minusSeconds(7 * 86400L)))
            .addValue("now", Timestamp.from(now));
        var named = new org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate(jdbc);
        return named.query("""
            SELECT instrument_key, ltp, previous_close, open_interest,
                   exchange_at, observed_at
            FROM customer_fno_verified_display_snapshot
            WHERE instrument_key IN (:keys) AND retain_until >= :today
              AND exchange_at >= :oldest AND exchange_at <= :now
            """, parameters, (java.sql.ResultSet rs) -> {
                var found = new java.util.HashMap<String, LastTrade>();
                while (rs.next()) {
                    found.put(rs.getString("instrument_key"),
                        new LastTrade(rs.getBigDecimal("ltp"),
                        rs.getBigDecimal("previous_close"),
                        (Long) rs.getObject("open_interest"),
                        rs.getTimestamp("exchange_at").toInstant(),
                        rs.getTimestamp("observed_at").toInstant()));
                }
                return java.util.Map.copyOf(found);
            });
    }

    public Optional<LastTrade> latest(String instrumentKey) {
        return Optional.ofNullable(latestAll(
            instrumentKey == null ? List.of() : List.of(instrumentKey))
            .get(instrumentKey));
    }

    @Scheduled(cron = "0 10 4 * * *", zone = "Asia/Kolkata")
    public void purgeExpired() {
        jdbc.update("DELETE FROM customer_fno_verified_display_snapshot WHERE retain_until < ?",
            Date.valueOf(clock.instant().atZone(IST).toLocalDate()));
    }
}
