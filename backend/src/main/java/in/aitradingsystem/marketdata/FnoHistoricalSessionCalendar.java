package in.aitradingsystem.marketdata;

import java.sql.Date;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Objects;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** Strict original-time calendar for historical F&O research only.
 * A missing or conflicting day is UNKNOWN, not a weekend or holiday.
 * Operational scheduling is deliberately unaffected. */
@Component
public final class FnoHistoricalSessionCalendar {
    static final String DOMAIN = "nse_fo_trading_calendar";
    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    private final JdbcTemplate jdbc;
    private final GoldenSourceRightsRepository rights;
    private final Clock clock;

    @Autowired
    public FnoHistoricalSessionCalendar(JdbcTemplate jdbc, GoldenSourceRightsRepository rights) {
        this(jdbc, rights, Clock.systemUTC());
    }

    FnoHistoricalSessionCalendar(JdbcTemplate jdbc, GoldenSourceRightsRepository rights, Clock clock) {
        this.jdbc = Objects.requireNonNull(jdbc);
        this.rights = Objects.requireNonNull(rights);
        this.clock = Objects.requireNonNull(clock);
    }

    record Attested(String provider, String status, OffsetDateTime close,
                    String digest) {}

    /** Only a verified CLOSED record returns false. Unknown throws. */
    public boolean isTradingDay(LocalDate date, Instant asOf) {
        Instant now = clock.instant();
        if (date == null || asOf == null || asOf.isAfter(now)
                || date.isAfter(asOf.atZone(IST).toLocalDate()))
            throw unavailable();
        var cutoff = OffsetDateTime.ofInstant(asOf, ZoneOffset.UTC);
        List<Attested> records = jdbc.query("""
            SELECT DISTINCT ON (e.provider_id) p.provider_code, e.session_status,
                e.session_close_at, e.calendar_evidence_digest
            FROM golden_historical_exchange_session_evidence e
            JOIN golden_data_provider p ON p.provider_id=e.provider_id
            WHERE e.exchange='NSE' AND e.source_domain=? AND e.session_date=?
                AND p.source_kind='EXTERNAL'
                AND p.provider_code IN ('UPSTOX_ANALYTICS','ANGEL_ONE_SMARTAPI')
                AND e.first_available_at<=? AND e.verified_at<=? AND e.captured_at<=?
            ORDER BY e.provider_id,e.source_revision DESC
            """, (rs,i) -> new Attested(rs.getString("provider_code"),
                rs.getString("session_status"),
                rs.getObject("session_close_at",OffsetDateTime.class),
                rs.getString("calendar_evidence_digest")),
            DOMAIN, Date.valueOf(date), cutoff, cutoff, cutoff);
        if (records == null || records.size() != 1) throw unavailable();
        var row = records.getFirst();
        if (row == null || !List.of("UPSTOX_ANALYTICS","ANGEL_ONE_SMARTAPI")
                .contains(row.provider()) || row.digest()==null
                || !row.digest().matches("[a-f0-9]{64}")) throw unavailable();
        for (var use : List.of(GoldenSourceRightsRepository.Use.RAW_RETENTION,
                               GoldenSourceRightsRepository.Use.MODEL_INFERENCE)) {
            // Both historical rights and current research-use rights are
            // mandatory; a subsequently revoked grant cannot be reused.
            if (!rights.isPermitted(row.provider(),DOMAIN,use,asOf)
                    || !rights.isPermitted(row.provider(),DOMAIN,use,now))
                throw unavailable();
        }
        if ("CLOSED".equals(row.status()) && row.close()==null) return false;
        if ("TRADING".equals(row.status()) && row.close()!=null
                && row.close().atZoneSameInstant(IST).toLocalDate().equals(date))
            return true;
        throw unavailable();
    }

    private static IllegalStateException unavailable() {
        return new IllegalStateException("ORIGINAL_FNO_CALENDAR_UNAVAILABLE");
    }
}
