package in.aitradingsystem.operations;

import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

/** Bounded, resumable drain of unprojected durable failures. No timestamp cursor can skip a late commit. */
@Service
public class OperationalIncidentRefreshService {
    private final JdbcClient jdbc;
    private final OperationalIncidentProjector projector;
    public OperationalIncidentRefreshService(JdbcClient jdbc, OperationalIncidentProjector projector) {
        this.jdbc = jdbc; this.projector = projector;
    }

    public ProjectionResult refresh(OperationalHealthService.HealthSnapshot health) {
        int processed = 0;
        var backfills = jdbc.sql("""
            SELECT c.checkpoint_id::text AS id,c.run_id::text AS run FROM candle_backfill_checkpoint c
             WHERE c.status = 'FAILED' AND NOT EXISTS (SELECT 1 FROM notification_source_projection p
                WHERE p.source_type = 'BACKFILL_FAILURE' AND p.source_id = c.checkpoint_id::text)
             ORDER BY c.checkpoint_id LIMIT 21
            """).query((rs,row) -> new Source(rs.getString("id"),rs.getString("run"))).list();
        for (var source : backfills.stream().limit(20).toList()) {
            projector.project("BACKFILL_FAILURE",source.id(),"Historical candle backfill failed",
                "Review the persisted backfill checkpoint and provider readiness.","BACKFILL_FAILURE:"+source.run());
            processed++;
        }
        var scenarios = jdbc.sql("""
            SELECT r.id::text FROM research_scenario_run r WHERE r.status = 'FAILED'
             AND NOT EXISTS (SELECT 1 FROM notification_source_projection p
                WHERE p.source_type = 'SCENARIO_FAILURE' AND p.source_id = r.id::text)
             ORDER BY r.created_at,r.id LIMIT 21
            """).query(String.class).list();
        for (String id : scenarios.stream().limit(20).toList()) {
            projector.project("SCENARIO_FAILURE",id,"Scenario research failed",
                "Review the persisted scenario run. Research remains advisory.","SCENARIO_FAILURE:"+id);
            processed++;
        }
        if (health != null) for (var component : health.components()) {
            if ("angelOne".equals(component.component()) && (component.state() == OperationalHealthService.State.UNAVAILABLE
                    || component.state() == OperationalHealthService.State.DEGRADED)) {
                projector.project("PROVIDER_OUTAGE",component.reasonCode()+":"+component.asOf(),"Angel One readiness is degraded",
                    "The stored broker-session/read-only health evidence is degraded. No automatic login was attempted.","PROVIDER_OUTAGE:ANGEL_ONE");
            }
            if ("marketData".equals(component.component()) && "STALE_PERSISTED_EVIDENCE".equals(component.reasonCode())) {
                projector.project("MARKET_DATA_STALE",String.valueOf(component.asOf()),"Persisted market data is stale",
                    "The latest aggregate one-minute bar exceeds the diagnostic freshness limit during the cash session.","MARKET_DATA_STALE:ACTIVE_CATALOGUE");
            }
        }
        return new ProjectionResult(processed, backfills.size()>20 || scenarios.size()>20);
    }

    /** Called after a failed backtest transaction has returned/rolled back, never with raw exception contents. */
    public void backtestFailed(long instrumentId) {
        projector.project("BACKTEST_FAILURE",UUID.randomUUID().toString(),"Historical backtest failed",
            "A backtest execution failed unexpectedly. Review the server-side diagnostic evidence.","BACKTEST_FAILURE:"+instrumentId);
    }
    private record Source(String id,String run) {}
    public record ProjectionResult(int durableFailuresProcessed,boolean backlogRemaining) {}
}
