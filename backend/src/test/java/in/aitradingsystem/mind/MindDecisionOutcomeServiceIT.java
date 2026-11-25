package in.aitradingsystem.mind;

import static org.assertj.core.api.Assertions.assertThat;

import in.aitradingsystem.scanner.ResearchScanRepository;
import in.aitradingsystem.support.AbstractPostgresIT;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

class MindDecisionOutcomeServiceIT extends AbstractPostgresIT {
    @Autowired JdbcClient jdbc;
    @Autowired ResearchScanRepository scans;
    @Autowired MindDecisionOutcomeService outcomes;

    @Test
    void listsOnlySucceededRunsAndTheirOutcomesWithReasons() {
        // Newest-first ordering, so these rows sit at the top of the bounded result.
        Instant completed = Instant.now().plus(Duration.ofMinutes(5));
        UUID ok = run("SUCCEEDED", completed);
        UUID partial = run("PARTIAL", completed.plusSeconds(60));
        Instant cutoff = completed.minusSeconds(120);
        scans.saveOutcome(ok, 7001L, "MA_RECLAIM_V1", "CANDIDATE", "CANDIDATE", cutoff);
        scans.saveOutcome(ok, 7002L, "MA_RECLAIM_V1", "NO_SIGNAL", "NO_SIGNAL", cutoff);
        scans.saveOutcome(ok, 7003L, "SMA_TREND_VOLUME_V1", "NOT_EVALUATED", "INSUFFICIENT_HISTORY", null);
        scans.saveOutcome(partial, 7004L, "MA_RECLAIM_V1", "CANDIDATE", "CANDIDATE", cutoff);

        var result = outcomes.inspect();

        var mine = result.outcomes().stream().filter(o -> o.instrumentId() >= 7001L && o.instrumentId() <= 7004L).toList();
        // The PARTIAL run's outcome is never listed as evidence.
        assertThat(mine).extracting(MindDecisionOutcomeService.Outcome::instrumentId).containsExactlyInAnyOrder(7001L, 7002L, 7003L);
        assertThat(mine).extracting(MindDecisionOutcomeService.Outcome::outcome)
            .containsExactlyInAnyOrder("CANDIDATE", "NO_SIGNAL", "NOT_EVALUATED");
        assertThat(mine).filteredOn(o -> o.instrumentId() == 7003L).singleElement()
            .satisfies(o -> assertThat(o.reasonCode()).isEqualTo("INSUFFICIENT_HISTORY"));
        assertThat(result.runs()).anyMatch(r -> r.scanRunId().equals(ok.toString()) && r.candidates() == 1
            && r.noSignal() == 1 && r.notEvaluated() == 1);
        assertThat(result.runs()).noneMatch(r -> r.scanRunId().equals(partial.toString()));
        assertThat(result.contract()).isEqualTo(MindDecisionOutcomeService.CONTRACT);
        assertThat(result.limitations()).isNotEmpty();
    }

    private UUID run(String status, Instant completedAt) {
        UUID id = UUID.randomUUID();
        jdbc.sql("""
            INSERT INTO research_scan_run(id,interval,status,requested_limit,scanned_count,candidate_count,
                strategy_set,started_at,completed_at)
            VALUES (:id,'FIVE_MINUTE',:status,1,1,1,'MA_RECLAIM_V1',:started,:completed)
            """)
            .param("id", id).param("status", status)
            .param("started", Timestamp.from(completedAt.minusSeconds(300)))
            .param("completed", Timestamp.from(completedAt))
            .update();
        return id;
    }
}
