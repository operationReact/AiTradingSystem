package in.aitradingsystem.operations;

import in.aitradingsystem.marketdata.IndianCashMarketCalendar;
import in.aitradingsystem.provider.angelone.AngelOneHealthService;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

/** Diagnostic snapshot only: never logs in, calls a provider, mutates execution controls or authorizes a trade. */
@Service
public class OperationalHealthService {
    private final JdbcClient jdbc;
    private final AngelOneHealthService angelOne;
    private final IndianCashMarketCalendar calendar;
    private final Clock clock;
    private final long catalogueMaxAge, marketMaxAge, newsMaxAge, scannerMaxAge;

    public OperationalHealthService(JdbcClient jdbc, AngelOneHealthService angelOne, IndianCashMarketCalendar calendar,
        Clock clock, @Value("${app.operations.catalogue-max-age-seconds:86400}") long catalogueMaxAge,
        @Value("${app.operations.market-max-age-seconds:300}") long marketMaxAge,
        @Value("${app.operations.news-max-age-seconds:3600}") long newsMaxAge,
        @Value("${app.operations.scanner-max-age-seconds:3600}") long scannerMaxAge) {
        this.jdbc = jdbc; this.angelOne = angelOne; this.calendar = calendar; this.clock = clock;
        if (catalogueMaxAge <= 0 || marketMaxAge <= 0 || newsMaxAge <= 0 || scannerMaxAge <= 0)
            throw new IllegalArgumentException("Operational freshness limits must be positive");
        this.catalogueMaxAge = catalogueMaxAge; this.marketMaxAge = marketMaxAge;
        this.newsMaxAge = newsMaxAge; this.scannerMaxAge = scannerMaxAge;
    }

    public HealthSnapshot snapshot() {
        Instant now = clock.instant();
        var provider = angelOne.status(); // status() is explicitly network-free; do not call testConnection().
        State providerState = switch (provider.state()) {
            case AUTHENTICATED -> provider.sessionActive() ? State.HEALTHY : State.UNKNOWN;
            case NOT_CONFIGURED, CONFIGURED_UNVERIFIED -> State.UNKNOWN;
            case SESSION_EXPIRED, DEGRADED -> State.DEGRADED;
            case UNAVAILABLE -> State.UNAVAILABLE;
        };
        Component providerComponent = component("angelOne", providerState, provider.lastSuccessAt(), now, provider.state().name());
        try {
            jdbc.sql("SELECT 1").query(Integer.class).single();
            List<Component> components = new ArrayList<>();
            components.add(component("database", State.HEALTHY, now, now, "READ_SUCCEEDED"));
            components.add(providerComponent);
            Instant catalogue = instant("SELECT activated_at FROM instrument_catalogue_state WHERE singleton_id = 1 AND active_version IS NOT NULL");
            components.add(freshness("catalogue", catalogue, catalogueMaxAge, now, false));
            Instant market = jdbc.sql("""
                SELECT max(c.candle_time) FROM market_candle c JOIN instrument_catalogue_state s
                  ON s.singleton_id = 1 AND s.active_version = c.catalogue_version
                 WHERE c.interval = 'ONE_MINUTE' AND c.candle_time <= :now
                """).param("now", ts(now)).query(OffsetDateTime.class).optional().map(OffsetDateTime::toInstant).orElse(null);
            boolean idle = !calendar.isInsideCashSession(now);
            components.add(freshness("marketData", market, marketMaxAge, now, idle));
            components.add(freshness("news", instant("SELECT max(fetched_at) FROM news_item"), newsMaxAge, now, false));
            Job scanner = lastJob("SELECT status,COALESCE(completed_at,started_at) AS at FROM research_scan_run ORDER BY started_at DESC,id DESC LIMIT 1");
            Component scannerState = scanner == null ? component("scanner", State.UNKNOWN, null, now, "NO_RUN_EVIDENCE")
                : component("scanner", "UNKNOWN".equals(scanner.status()) ? State.UNKNOWN : "FAILED".equals(scanner.status()) ? State.DEGRADED
                    : age(scanner.at(), now) > scannerMaxAge ? State.DEGRADED : State.HEALTHY,
                    scanner.at(), now, "FAILED".equals(scanner.status()) ? "LAST_RUN_FAILED" : "LAST_RUN_" + scanner.status());
            components.add(scannerState);
            Job reconciliation = lastJob("SELECT status,COALESCE(completed_at,started_at) AS at FROM paper_reconciliation_run ORDER BY started_at DESC,id DESC LIMIT 1");
            Instant lastFill = instant("SELECT max(filled_at) FROM paper_fill");
            boolean behind = reconciliation != null && lastFill != null && reconciliation.at().isBefore(lastFill);
            components.add(reconciliation == null ? component("reconciliation", State.UNKNOWN, null, now, "NO_RUN_EVIDENCE")
                : component("reconciliation", "CLEAN".equals(reconciliation.status()) && !behind ? State.HEALTHY : State.DEGRADED,
                    reconciliation.at(), now, behind ? "RECONCILIATION_BEHIND_EXECUTION" : "LAST_RUN_" + reconciliation.status()));
            String mode = jdbc.sql("SELECT active_mode FROM trading_execution_mode WHERE id = 1").query(String.class).optional().orElse("UNKNOWN");
            components.add(component("executionMode", "PAPER".equals(mode) ? State.HEALTHY : State.UNSAFE, now, now,
                "PAPER".equals(mode) ? "PAPER_ONLY" : "UNSUPPORTED_EXECUTION_MODE"));
            boolean halted = jdbc.sql("SELECT new_orders_halted FROM trading_control WHERE id = 1").query(Boolean.class).single();
            components.add(component("emergencyStop", halted ? State.HALTED : State.HEALTHY, now, now, halted ? "OPERATOR_HALT_ACTIVE" : "NOT_HALTED"));
            State overall = components.stream().anyMatch(c -> c.state() == State.UNSAFE) ? State.UNSAFE : halted ? State.HALTED
                : components.stream().anyMatch(c -> c.state() == State.DEGRADED || c.state() == State.UNAVAILABLE || c.state() == State.UNKNOWN)
                    ? State.DEGRADED : State.HEALTHY;
            return new HealthSnapshot(now, overall, mode, false, halted, List.copyOf(components));
        } catch (DataAccessException ex) {
            // Do not expose exception text, connection URLs, usernames, SQL or provider secrets in health responses.
            List<Component> failed = new ArrayList<>();
            failed.add(component("database", State.UNAVAILABLE, null, now, "DATABASE_READ_FAILED"));
            failed.add(providerComponent);
            for (String key : List.of("catalogue","marketData","news","scanner","reconciliation","executionMode","emergencyStop"))
                failed.add(component(key, State.UNKNOWN, null, now, "DATABASE_EVIDENCE_UNAVAILABLE"));
            return new HealthSnapshot(now, State.UNAVAILABLE, "UNKNOWN", false, null, List.copyOf(failed));
        }
    }

    private Component freshness(String key, Instant at, long maxAge, Instant now, boolean idle) {
        return component(key, at == null ? State.UNKNOWN : at.isAfter(now) ? State.DEGRADED
            : idle ? State.IDLE : age(at, now) > maxAge ? State.DEGRADED : State.HEALTHY,
            at, now, at == null ? "NO_PERSISTED_EVIDENCE" : at.isAfter(now) ? "FUTURE_TIMESTAMP"
                : idle ? "NON_TRADING_SESSION" : age(at, now) > maxAge ? "STALE_PERSISTED_EVIDENCE" : "WITHIN_FRESHNESS_LIMIT");
    }
    private Instant instant(String sql) {
        return jdbc.sql(sql).query(OffsetDateTime.class).optional().map(OffsetDateTime::toInstant).orElse(null);
    }
    private Job lastJob(String sql) {
        return jdbc.sql(sql).query((rs,row) -> new Job(knownStatus(rs.getString("status")), rs.getObject("at",OffsetDateTime.class).toInstant())).optional().orElse(null);
    }
    private static String knownStatus(String value) {
        return value != null && java.util.Set.of("CREATED","RUNNING","SUCCEEDED","FAILED","PARTIAL","CLEAN","DIFFERENCES").contains(value)
            ? value : "UNKNOWN";
    }
    private static long age(Instant at, Instant now) { return at == null ? Long.MAX_VALUE : Math.max(0, Duration.between(at,now).getSeconds()); }
    private static Component component(String key, State state, Instant at, Instant now, String reason) {
        return new Component(key,state,at,at == null ? null : age(at,now),reason);
    }
    private static OffsetDateTime ts(Instant at) { return at.atOffset(ZoneOffset.UTC); }
    private record Job(String status, Instant at) {}
    public enum State { HEALTHY, DEGRADED, UNKNOWN, UNAVAILABLE, IDLE, HALTED, UNSAFE }
    public record Component(String component, State state, Instant asOf, Long ageSeconds, String reasonCode) {}
    public record HealthSnapshot(Instant checkedAt, State state, String executionMode, boolean liveAvailable,
                                 Boolean emergencyStopActive, List<Component> components) {}
}
