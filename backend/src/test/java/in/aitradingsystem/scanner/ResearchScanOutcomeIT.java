package in.aitradingsystem.scanner;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import in.aitradingsystem.support.AbstractPostgresIT;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

class ResearchScanOutcomeIT extends AbstractPostgresIT {
    @Autowired ResearchScanRepository scans;
    @Autowired JdbcClient jdbc;

    private static final Instant CUTOFF = Instant.parse("2026-11-13T09:30:00Z");

    @Test
    void recordsEveryOutcomeOnceAndNeverRewritesIt() {
        UUID run = UUID.randomUUID();
        scans.saveOutcome(run, 4401L, "MA_RECLAIM_V1", "NO_SIGNAL", "NO_SIGNAL", CUTOFF);
        scans.saveOutcome(run, 4401L, "MA_RECLAIM_V1", "CANDIDATE", "CANDIDATE", CUTOFF);
        scans.saveOutcome(run, 4402L, "SMA_TREND_VOLUME_V1", "NOT_EVALUATED", "NO_CANDLES", null);

        // The repeated key is ignored: the first recorded outcome is kept, never overwritten.
        String first = jdbc.sql("""
                SELECT outcome FROM research_scan_outcome
                 WHERE scan_run_id = :run AND instrument_id = 4401 AND strategy = 'MA_RECLAIM_V1'
                """).param("run", run).query(String.class).single();
        assertThat(first).isEqualTo("NO_SIGNAL");
        assertThat(jdbc.sql("SELECT COUNT(*) FROM research_scan_outcome WHERE scan_run_id = :run")
                .param("run", run).query(Long.class).single()).isEqualTo(2L);
    }

    @Test
    void rowsAreAppendOnly() {
        UUID run = UUID.randomUUID();
        scans.saveOutcome(run, 4403L, "MA_RECLAIM_V1", "NO_SIGNAL", "NO_SIGNAL", CUTOFF);

        assertThatThrownBy(() -> jdbc.sql("UPDATE research_scan_outcome SET outcome = 'CANDIDATE' WHERE scan_run_id = :run")
                .param("run", run).update())
            .hasMessageContaining("append-only");
        assertThatThrownBy(() -> jdbc.sql("DELETE FROM research_scan_outcome WHERE scan_run_id = :run")
                .param("run", run).update())
            .hasMessageContaining("append-only");
    }

    @Test
    void aCandidateOutcomeRequiresItsDataCutoff() {
        UUID run = UUID.randomUUID();
        assertThatThrownBy(() -> scans.saveOutcome(run, 4404L, "MA_RECLAIM_V1", "CANDIDATE", "CANDIDATE", null))
            .hasMessageContaining("ck_research_scan_outcome_candidate");
    }
}
