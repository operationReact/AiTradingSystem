package in.aitradingsystem.provider.upstox;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Durable, owner-private operational metadata. No candles, bearer tokens,
 * portfolios, model inputs or user balances are persisted here.
 */
@Repository
public class UpstoxAnalyticsProgressRepository {
    private static final ObjectMapper JSON = new ObjectMapper();
    private final JdbcTemplate jdbc;

    public UpstoxAnalyticsProgressRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public record Task(String code, String state, Long checked, Long verified,
                       Long issues, String reasonCode, Map<String, Object> details,
                       Instant lastAttemptAt, Instant lastSuccessAt, Instant updatedAt,
                       boolean stale) {}

    public List<Task> snapshot(Instant now) {
        return jdbc.query("""
            SELECT task_code, state, checked_count, verified_count, issue_count,
                   reason_code, details_json::text AS details,
                   last_attempt_at, last_success_at, updated_at
              FROM minttick_upstox_analytics_progress ORDER BY task_code
            """, (rs, index) -> row(rs, now));
    }

    private static Task row(ResultSet rs, Instant now) throws SQLException {
        String state = rs.getString("state");
        Instant updated = at(rs, "updated_at");
        Instant lastSuccess = at(rs, "last_success_at");
        // The source verification cadence is 30 minutes, GitHub 5 minutes.
        // A new PARTIAL result is not stale merely because it never fully passed.
        Instant freshness = "VERIFIED".equals(state) ? lastSuccess : updated;
        long freshnessSeconds = "SOURCE_VERIFICATION".equals(rs.getString("task_code"))
                ? 45 * 60 : 20 * 60;
        boolean stale = ("VERIFIED".equals(state) || "PARTIAL".equals(state))
                && (freshness == null || freshness.isBefore(now.minusSeconds(freshnessSeconds)));
        return new Task(rs.getString("task_code"), state,
                number(rs, "checked_count"), number(rs, "verified_count"),
                number(rs, "issue_count"), rs.getString("reason_code"),
                safeObject(rs.getString("details")), at(rs, "last_attempt_at"),
                lastSuccess, updated, stale);
    }

    private static Instant at(ResultSet rs, String name) throws SQLException {
        OffsetDateTime value = rs.getObject(name, OffsetDateTime.class);
        return value == null ? null : value.toInstant();
    }

    private static Long number(ResultSet rs, String name) throws SQLException {
        long value = rs.getLong(name);
        return rs.wasNull() ? null : value;
    }

    private static Map<String, Object> safeObject(String value) {
        try {
            if (value == null || value.length() > 12_000) return Map.of();
            Object parsed = JSON.readValue(value, Object.class);
            if (!(parsed instanceof Map<?, ?> entries)) return Map.of();
            var result = new java.util.LinkedHashMap<String, Object>();
            for (var entry : entries.entrySet()) {
                if (entry.getKey() instanceof String key && key.length() < 80) {
                    result.put(key, entry.getValue());
                }
            }
            return Map.copyOf(result);
        } catch (JsonProcessingException ignored) {
            return Map.of();
        }
    }

    /** Lease is durable and cross-instance. A crash releases ownership on expiry. */
    public UUID claim(String code, Instant now) {
        UUID token = UUID.randomUUID();
        List<UUID> claimed = jdbc.query("""
            UPDATE minttick_upstox_analytics_progress
               SET state = 'RUNNING', reason_code = NULL, last_attempt_at = ?,
                   lease_token = ?, lease_until = ?, updated_at = ?
             WHERE task_code = ?
               AND (lease_until IS NULL OR lease_until < ?)
            RETURNING lease_token
            """, (rs, row) -> rs.getObject(1, UUID.class),
                utc(now), token, utc(now.plusSeconds(180)), utc(now), code, utc(now));
        return claimed.isEmpty() ? null : token;
    }

    public void finish(String code, UUID token, String state,
                       Long checked, Long verified, Long issues,
                       String reasonCode, Map<String, Object> details, Instant now) {
        if (!List.of("VERIFIED", "PARTIAL", "FAILED", "BLOCKED").contains(state)) {
            throw new IllegalArgumentException("Invalid telemetry state");
        }
        if (reasonCode != null && !reasonCode.matches("[A-Z0-9_]{3,96}")) {
            throw new IllegalArgumentException("Invalid safe reason code");
        }
        String encoded;
        try {
            encoded = JSON.writeValueAsString(details == null ? Map.of() : details);
        } catch (JsonProcessingException ex) {
            encoded = "{}";
        }
        if (encoded.length() > 12000) encoded = "{}";
        jdbc.update("""
            UPDATE minttick_upstox_analytics_progress
               SET state = ?, checked_count = ?, verified_count = ?, issue_count = ?,
                   reason_code = ?, details_json = CAST(? AS jsonb),
                   last_success_at = CASE WHEN ? = 'VERIFIED' THEN ? ELSE last_success_at END,
                   updated_at = ?, lease_token = NULL, lease_until = NULL
             WHERE task_code = ? AND lease_token = ? AND lease_until > ?
            """, state, checked, verified, issues, reasonCode, encoded,
                state, utc(now), utc(now), code, token, utc(now));
    }

    public void disabled(String code, String reason, Instant now) {
        jdbc.update("""
            UPDATE minttick_upstox_analytics_progress
               SET state = 'DISABLED', reason_code = ?, updated_at = ?,
                   lease_token = NULL, lease_until = NULL
             WHERE task_code = ? AND state <> 'DISABLED'
            """, reason, utc(now), code);
    }

    private static OffsetDateTime utc(Instant now) {
        return OffsetDateTime.ofInstant(now, ZoneOffset.UTC);
    }
}
