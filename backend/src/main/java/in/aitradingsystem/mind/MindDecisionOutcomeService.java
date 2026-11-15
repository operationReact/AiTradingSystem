package in.aitradingsystem.mind;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * Read-only evidence of persisted per-strategy research outcomes.
 *
 * <p>Only SUCCEEDED scan runs are listed. PARTIAL and FAILED runs are never approval evidence.
 * Each outcome is CANDIDATE, NO_SIGNAL or NOT_EVALUATED with its reason code. A candidate is
 * research evidence only and places no order. Symbols come from the latest catalogue version
 * of the instrument and are shown as a display label, not as evidence identity.
 */
@Service
public class MindDecisionOutcomeService {
    public static final String CONTRACT = "mind.decision-outcomes.v1";
    static final int MAX_RUNS = 10;
    static final int MAX_ROWS = 100;
    private static final List<String> LIMITATIONS = List.of(
        "Only SUCCEEDED scan runs are listed. PARTIAL and FAILED runs are excluded.",
        "NO_SIGNAL means the strategy ran and found nothing. NOT_EVALUATED means it did not run, with its reason.",
        "A CANDIDATE is research evidence only. It places no order and enables no execution.");

    private final JdbcTemplate jdbc;
    private final Clock clock;

    public MindDecisionOutcomeService(JdbcTemplate jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    public record RunSummary(String scanRunId, String completedAt, String interval, long candidates,
                             long noSignal, long notEvaluated) {}

    public record Outcome(String scanRunId, String completedAt, String strategy, String outcome,
                          String reasonCode, long instrumentId, String tradingSymbol, String dataCutoffAt) {}

    public record Outcomes(String contract, Instant generatedAt, List<RunSummary> runs,
                           List<Outcome> outcomes, List<String> limitations) {}

    public Outcomes inspect() {
        Instant now = clock.instant();
        List<RunSummary> runs = jdbc.query("""
                SELECT r.id, r.completed_at, r.interval,
                       COUNT(*) FILTER (WHERE o.outcome = 'CANDIDATE') AS candidates,
                       COUNT(*) FILTER (WHERE o.outcome = 'NO_SIGNAL') AS no_signal,
                       COUNT(*) FILTER (WHERE o.outcome = 'NOT_EVALUATED') AS not_evaluated
                  FROM research_scan_run r
                  JOIN research_scan_outcome o ON o.scan_run_id = r.id
                 WHERE r.status = 'SUCCEEDED' AND r.completed_at IS NOT NULL
                 GROUP BY r.id, r.completed_at, r.interval
                 ORDER BY r.completed_at DESC, r.id DESC
                 LIMIT ?
                """, (rs, n) -> new RunSummary(
                    rs.getObject("id").toString(),
                    instant(rs.getObject("completed_at", OffsetDateTime.class)),
                    rs.getString("interval"),
                    rs.getLong("candidates"), rs.getLong("no_signal"), rs.getLong("not_evaluated")),
                MAX_RUNS);

        List<Outcome> outcomes = jdbc.query("""
                SELECT r.id AS run_id, r.completed_at, o.strategy, o.outcome, o.reason_code,
                       o.instrument_id, o.data_cutoff_at,
                       (SELECT i.trading_symbol FROM instrument_master i
                         WHERE i.instrument_id = o.instrument_id
                         ORDER BY i.catalogue_version DESC LIMIT 1) AS trading_symbol
                  FROM research_scan_outcome o
                  JOIN research_scan_run r ON r.id = o.scan_run_id
                 WHERE r.status = 'SUCCEEDED' AND r.completed_at IS NOT NULL
                 ORDER BY r.completed_at DESC, r.id DESC, o.strategy ASC, o.instrument_id ASC
                 LIMIT ?
                """, (rs, n) -> new Outcome(
                    rs.getObject("run_id").toString(),
                    instant(rs.getObject("completed_at", OffsetDateTime.class)),
                    rs.getString("strategy"),
                    rs.getString("outcome"),
                    rs.getString("reason_code"),
                    rs.getLong("instrument_id"),
                    rs.getString("trading_symbol"),
                    instant(rs.getObject("data_cutoff_at", OffsetDateTime.class))),
                MAX_ROWS);
        return new Outcomes(CONTRACT, now, runs, outcomes, LIMITATIONS);
    }

    private static String instant(OffsetDateTime value) {
        return value == null ? null : value.toInstant().toString();
    }
}
