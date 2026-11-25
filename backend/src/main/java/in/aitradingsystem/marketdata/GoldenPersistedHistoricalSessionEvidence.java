package in.aitradingsystem.marketdata;

import java.sql.Date;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Strict read-only V81 historical exchange session resolver. Requires an
 * independently registered external provider, a current RAW_RETENTION grant
 * for 'exchange_calendar' and a verified immutable session record.
 *
 * If there is no evidence or more than one qualifying source, return UNKNOWN.
 * Never guess from the weekday, current session calendar, or live broker feed.
 * No background importer or mutation endpoint is supplied.
 */
@Repository
public class GoldenPersistedHistoricalSessionEvidence implements GoldenHistoricalSessionEvidence {
    private static final String SOURCE_DOMAIN = "exchange_calendar";
    private final JdbcTemplate jdbc;
    private final GoldenSourceRightsRepository rights;
    private final Clock clock;

    @Autowired
    public GoldenPersistedHistoricalSessionEvidence(
            JdbcTemplate jdbc, GoldenSourceRightsRepository rights) {
        this(jdbc, rights, Clock.systemUTC());
    }

    GoldenPersistedHistoricalSessionEvidence(
            JdbcTemplate jdbc, GoldenSourceRightsRepository rights, Clock clock) {
        this.jdbc = java.util.Objects.requireNonNull(jdbc);
        this.rights = java.util.Objects.requireNonNull(rights);
        this.clock = java.util.Objects.requireNonNull(clock);
    }

    @Override
    public SessionEvidence resolve(String exchange, java.time.LocalDate sessionDate) {
        if (!("NSE".equals(exchange) || "BSE".equals(exchange))
                || sessionDate == null) {
            throw new IllegalArgumentException("historical exchange and session date required");
        }
        var now = OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
        // An older source revision is never silently deleted. The latest
        // already-published/verified record per provider is used. Conflicting
        // provider histories are not arbitrated using a winner by priority.
        List<Attested> rows = jdbc.query("""
                SELECT DISTINCT ON (e.provider_id) p.provider_code,
                       e.session_status, e.session_close_at,
                       e.calendar_evidence_digest, e.verified_at
                  FROM golden_historical_exchange_session_evidence e
                  JOIN golden_data_provider p ON p.provider_id = e.provider_id
                 WHERE e.exchange = ? AND e.session_date = ?
                   AND e.source_domain = ?
                   AND p.source_kind = 'EXTERNAL'
                   AND e.captured_at <= ?
                   AND e.verified_at <= ?
                   AND e.first_available_at <= ?
                 ORDER BY e.provider_id, e.source_revision DESC
                """, (rs, i) -> new Attested(
                        rs.getString("provider_code"),
                        Status.valueOf(rs.getString("session_status")),
                        rs.getObject("session_close_at", OffsetDateTime.class),
                        rs.getString("calendar_evidence_digest"),
                        rs.getObject("verified_at", OffsetDateTime.class)),
                exchange, Date.valueOf(sessionDate), SOURCE_DOMAIN, now, now, now);
        if (rows.size() != 1) return unknown();
        Attested a = rows.get(0);
        if (!rights.isPermitted(a.providerCode(), SOURCE_DOMAIN,
                GoldenSourceRightsRepository.Use.RAW_RETENTION, clock.instant())) {
            return unknown();
        }
        return new SessionEvidence(a.status(),
                a.closeAt() == null ? null : a.closeAt().toInstant(),
                a.evidenceDigest(), a.verifiedAt().toInstant());
    }

    private static SessionEvidence unknown() {
        return new SessionEvidence(Status.UNKNOWN, null, null, null);
    }

    private record Attested(String providerCode, Status status,
                            OffsetDateTime closeAt, String evidenceDigest,
                            OffsetDateTime verifiedAt) {}
}
