package in.aitradingsystem.mind;

import static org.assertj.core.api.Assertions.assertThat;

import in.aitradingsystem.support.AbstractPostgresIT;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

class MindDecisionHistoryServiceIT extends AbstractPostgresIT {
    // The database is shared with other integration tests, which also write candidates, so every
    // fingerprint here is unique to this run and results are scoped to the scan runs created here.
    // Scanner fingerprints are 64 lowercase hex characters (database check constraint).
    private static final String VALID = hex64();
    private static final String FUTURE = hex64();
    private static final String PARTIAL = hex64();
    private static final Instant SIGNAL = Instant.parse("2026-11-13T09:30:00Z");

    @Autowired JdbcClient jdbc;
    @Autowired MindDecisionHistoryService history;
    private final List<UUID> runs = new ArrayList<>();

    @AfterEach
    void cleanup() {
        for (UUID id : runs) {
            jdbc.sql("DELETE FROM research_scan_candidate WHERE scan_run_id = :id").param("id", id).update();
            jdbc.sql("DELETE FROM research_scan_run WHERE id = :id").param("id", id).update();
        }
        runs.clear();
    }

    @Test
    void listsOnlySucceededRunsWithValidLineageAndNothingExecutable() {
        UUID ok = run("SUCCEEDED", Instant.parse("2026-11-13T10:00:00Z"));
        UUID partial = run("PARTIAL", Instant.parse("2026-11-13T11:00:00Z"));
        candidate(ok, 1, VALID, SIGNAL, SIGNAL);
        // Data cutoff before the signal means the decision used later information: never listed.
        candidate(ok, 2, FUTURE, SIGNAL, SIGNAL.minusSeconds(60));
        // A PARTIAL run is never candidate approval, even with valid lineage.
        candidate(partial, 3, PARTIAL, SIGNAL, SIGNAL);

        var ownRuns = List.of(ok.toString(), partial.toString());
        var rows = history.inspect().decisions().stream()
            .filter(d -> ownRuns.contains(d.scanRunId()))
            .toList();

        assertThat(rows).extracting(MindDecisionHistoryService.Decision::candidateFingerprint)
            .containsExactly(VALID);
        var row = rows.getFirst();
        assertThat(row.decisionPosture()).isEqualTo("RECORDED_CANDIDATE");
        // The original persisted scan instrument ID, never a ticker-derived substitute.
        assertThat(row.instrumentId()).isEqualTo(1L);
        assertThat(row.paperOrderSubmitted()).isFalse();
        assertThat(row.executionEnabled()).isFalse();
        assertThat(row.liveMoney()).isFalse();
        assertThat(row.reasons()).containsExactly("TREND", "VOLUME");
    }

    private static String hex64() {
        return (UUID.randomUUID().toString() + UUID.randomUUID().toString())
            .replace("-", "").substring(0, 64);
    }

    private UUID run(String status, Instant completedAt) {
        UUID id = UUID.randomUUID();
        runs.add(id);
        jdbc.sql("""
            INSERT INTO research_scan_run(id,interval,status,requested_limit,scanned_count,candidate_count,
                strategy_set,started_at,completed_at)
            VALUES (:id,'FIVE_MINUTE',:status,1,1,1,'MA_RECLAIM_V1',:started,:completed)
            """)
            .param("id", id).param("status", status)
            .param("started", java.sql.Timestamp.from(completedAt.minusSeconds(300)))
            .param("completed", java.sql.Timestamp.from(completedAt))
            .update();
        return id;
    }

    private void candidate(UUID run, long instrument, String fingerprint, Instant signal, Instant cutoff) {
        jdbc.sql("""
            INSERT INTO research_scan_candidate(scan_run_id,instrument_id,trading_symbol,exchange,interval,strategy,
                score,entry_price,stop_price,target_price,signal_candle_time,reasons,candidate_fingerprint,
                strategy_config_version,catalogue_version,data_cutoff_at,created_at)
            VALUES (:run,:instrument,'ITSYM','NSE','FIVE_MINUTE','MA_RECLAIM_V1',0.5,100,95,110,
                :signal,'TREND|VOLUME',:fp,1,NULL,:cutoff,:signal)
            """)
            .param("run", run).param("instrument", instrument)
            .param("signal", java.sql.Timestamp.from(signal))
            .param("cutoff", java.sql.Timestamp.from(cutoff))
            .param("fp", fingerprint)
            .update();
    }
}
