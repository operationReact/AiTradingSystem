package in.aitradingsystem.mind;

import com.fasterxml.jackson.databind.ObjectMapper;
import in.aitradingsystem.news.feature.NewsContext;
import in.aitradingsystem.news.feature.NewsFeaturePolicy;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * Read-only history of persisted research candidates from SUCCEEDED scan runs.
 *
 * <p>Each row is a recorded research candidate, not an order or a forecast. It carries no
 * calibrated confidence and no trading outcome. Abstentions are not persisted per decision, which
 * this response states explicitly rather than inventing them.
 */
@Service
public class MindDecisionHistoryService {
    public static final String CONTRACT = "mind.decision-history.v1";
    public static final int MAX_ROWS = 50;
    private static final List<String> LIMITATIONS = List.of(
        "Only candidates from SUCCEEDED scan runs are listed. PARTIAL and FAILED runs are excluded.",
        "Abstentions and no-signal outcomes are not persisted per decision, so they are not listed.",
        "Scanner rank is not shown because it is not a calibrated confidence.",
        "A recorded candidate is research evidence only. It places no order and enables no execution.",
        "News context is an advisory annotation captured at scan time. Null news means it was not recorded for that candidate, not that no news existed.");

    private final JdbcTemplate jdbc;
    private final Clock clock;
    private final ObjectMapper mapper;

    public MindDecisionHistoryService(JdbcTemplate jdbc, Clock clock, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.clock = clock;
        this.mapper = mapper;
    }

    public record Decision(String scanRunId, String runCompletedAt, String strategy,
                           Integer strategyVersion, String catalogueVersion, String tradingSymbol,
                           String exchange, String interval, String signalCandleTime,
                           String dataCutoffAt, String candidateFingerprint, List<String> reasons,
                           String decisionPosture, boolean paperOrderSubmitted,
                           boolean executionEnabled, boolean liveMoney,
                           // Always serialized: null is an explicit "not recorded", never an omitted field.
                           @com.fasterxml.jackson.annotation.JsonInclude(
                               com.fasterxml.jackson.annotation.JsonInclude.Include.ALWAYS)
                           NewsContext news, Long instrumentId) {}

    public record History(String contract, Instant generatedAt, int limit, List<Decision> decisions,
                          List<String> limitations) {}

    private record Row(Decision decision, Instant signalAt, Instant dataCutoffAt) {}

    public History inspect() {
        Instant now = clock.instant();
        List<Row> rows = jdbc.query("""
                SELECT r.id AS run_id, r.completed_at AS run_completed_at,
                       c.strategy, c.strategy_config_version, c.catalogue_version,
                       c.instrument_id, c.trading_symbol, c.exchange, c.interval, c.signal_candle_time,
                       c.data_cutoff_at, c.candidate_fingerprint, c.reasons,
                       c.news_context::text AS news_context, c.news_context_contract
                  FROM research_scan_candidate c
                  JOIN research_scan_run r ON r.id = c.scan_run_id
                 WHERE r.status = 'SUCCEEDED' AND r.completed_at IS NOT NULL
                   AND c.signal_candle_time IS NOT NULL AND c.data_cutoff_at IS NOT NULL
                   AND c.data_cutoff_at >= c.signal_candle_time
                 ORDER BY r.completed_at DESC, c.signal_candle_time DESC,
                          c.trading_symbol ASC, c.strategy ASC
                 LIMIT ?
                """, (rs, n) -> {
                    Instant signalAt = rs.getObject("signal_candle_time", OffsetDateTime.class).toInstant();
                    Instant cutoffAt = rs.getObject("data_cutoff_at", OffsetDateTime.class).toInstant();
                    Decision decision = new Decision(
                        rs.getObject("run_id").toString(),
                        instant(rs.getObject("run_completed_at", OffsetDateTime.class)),
                        rs.getString("strategy"),
                        (Integer) rs.getObject("strategy_config_version"),
                        stringOrNull(rs.getObject("catalogue_version")),
                        rs.getString("trading_symbol"),
                        rs.getString("exchange"),
                        rs.getString("interval"),
                        signalAt.toString(),
                        cutoffAt.toString(),
                        rs.getString("candidate_fingerprint"),
                        splitReasons(rs.getString("reasons")),
                        "RECORDED_CANDIDATE",
                        false, false, false,
                        parseNews(rs.getString("news_context"), rs.getString("news_context_contract")),
                        rs.getLong("instrument_id") > 0 ? rs.getLong("instrument_id") : null);
                    return new Row(decision, signalAt, cutoffAt);
                }, MAX_ROWS);
        // Fail closed in Java too: any row whose lineage is not valid is dropped, never shown.
        List<Decision> decisions = rows.stream()
            .filter(row -> lineageValid(row.signalAt(), row.dataCutoffAt()))
            .map(Row::decision)
            .toList();
        return new History(CONTRACT, now, MAX_ROWS, decisions, LIMITATIONS);
    }

    /** Fail-closed lineage: a candidate whose data cutoff precedes its signal is treated as future-informed. */
    static boolean lineageValid(Instant signalAt, Instant dataCutoffAt) {
        return signalAt != null && dataCutoffAt != null && !dataCutoffAt.isBefore(signalAt);
    }

    static List<String> splitReasons(String reasons) {
        if (reasons == null || reasons.isBlank()) return List.of();
        return Arrays.stream(reasons.split("\\|")).filter(v -> !v.isBlank()).toList();
    }

    /** Only the exact stored contract is read. Anything else is shown as not recorded, never as evidence. */
    NewsContext parseNews(String json, String contract) {
        if (json == null || json.isBlank() || !NewsFeaturePolicy.CONTRACT.equals(contract)) return null;
        try {
            return mapper.readValue(json, NewsContext.class);
        } catch (Exception e) {
            return null;
        }
    }

    private static String instant(OffsetDateTime value) {
        return value == null ? null : value.toInstant().toString();
    }

    private static String stringOrNull(Object value) {
        return value == null ? null : value.toString();
    }
}
