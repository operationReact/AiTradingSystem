package in.aitradingsystem.provider.upstox;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class UpstoxWorkerReportReaderTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Instant NOW = Instant.parse("2026-11-14T01:00:00Z");

    @Test
    void reportsActualFiveWorkerNotesWithoutConflatingRuntime() throws Exception {
        var sample = JSON.readTree("""
          [
            {"user":{"login":"operationReact"},"created_at":"2026-11-14T00:45:00Z",
             "body":"MINTTICK_UPSTOX_WORKER_V1\\nworker=2\\nscope=CANONICAL_DATA\\nresult=MERGED\\npr=1010\\nhead=NONE\\nci=PASS\\nruntime=NOT_OBSERVED\\nblocker=NO_PERSISTED_RECEIPTS\\nnext=QUERY_REAL_DB"},
            {"user":{"login":"untrusted"},"created_at":"2026-11-14T00:45:00Z",
             "body":"MINTTICK_UPSTOX_WORKER_V1\\nworker=1\\nscope=SOURCE_INGESTION\\nresult=MERGED\\npr=2000\\nhead=NONE\\nci=PASS\\nruntime=VERIFIED\\nblocker=NONE\\nnext=TRADE"}
          ]""");
        var result = UpstoxWorkerReportReader.summarize(sample, NOW);
        assertThat(result.fresh()).isEqualTo(1);
        assertThat(result.reports()).isEqualTo(1);
        var rows = (java.util.List<?>) result.details().get("workerReports");
        assertThat(rows).hasSize(5);
        var second = (java.util.Map<?, ?>) rows.get(1);
        assertThat(second.get("state")).isEqualTo("FRESH");
        assertThat(second.get("result")).isEqualTo("MERGED");
        assertThat(second.get("runtimeClaim")).isEqualTo("NOT_OBSERVED");
        assertThat(((java.util.Map<?, ?>) rows.get(0)).get("state")).isEqualTo("NO_REPORT");
    }

    @Test
    void staleReportIsNotWorkerExecutionSuccess() throws Exception {
        var sample = JSON.readTree("""
          [{"user":{"login":"operationReact"},"created_at":"2026-11-13T21:00:00Z",
          "body":"MINTTICK_UPSTOX_WORKER_V1\\nworker=1\\nscope=SOURCE_INGESTION\\nresult=PR_OPEN\\npr=1020\\nhead=NONE\\nci=PENDING\\nruntime=NOT_OBSERVED\\nblocker=RIGHTS_UNVERIFIED\\nnext=REVIEW_CI"}]
        """);
        var result = UpstoxWorkerReportReader.summarize(sample, NOW);
        assertThat(result.reports()).isEqualTo(1);
        assertThat(result.fresh()).isZero();
        var rows = (java.util.List<?>) result.details().get("workerReports");
        assertThat(((java.util.Map<?, ?>) rows.get(0)).get("state")).isEqualTo("STALE");
    }

    @Test
    void rejectsMalformedOrOversizedPages() throws Exception {
        assertThatThrownBy(() -> UpstoxWorkerReportReader.summarize(
                JSON.readTree("{}"), NOW)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> UpstoxWorkerReportReader.summarize(
                JSON.readTree("[" + "{},".repeat(101) + "{}]"), NOW))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void runDeltaShowsEvidenceChangesNotRepeatedPolls() throws Exception {
        var sample = JSON.readTree("""
          [
            {"user":{"login":"operationReact"},"created_at":"2026-11-14T00:10:00Z",
             "body":"MINTTICK_UPSTOX_WORKER_V1\\nworker=1\\nscope=SOURCE_INGESTION\\nresult=PR_OPEN\\npr=1030\\nhead=aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa\\nci=PENDING\\nruntime=NOT_OBSERVED\\nblocker=RIGHTS_UNVERIFIED\\nnext=CHECK_CI"},
            {"user":{"login":"operationReact"},"created_at":"2026-11-14T00:40:00Z",
             "body":"MINTTICK_UPSTOX_WORKER_V1\\nworker=1\\nscope=SOURCE_INGESTION\\nresult=PR_OPEN\\npr=1030\\nhead=bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb\\nci=PENDING\\nruntime=NOT_OBSERVED\\nblocker=RIGHTS_UNVERIFIED\\nnext=CHECK_CI"}
          ]""");
        var result = UpstoxWorkerReportReader.summarize(sample, NOW);
        var rows = (java.util.List<?>) result.details().get("workerReports");
        var worker = (java.util.Map<?, ?>) rows.get(0);
        assertThat(worker.get("reports12h")).isEqualTo(2);
        assertThat(worker.get("lastRunChange")).isEqualTo("COMMIT_REPORTED");
        assertThat(worker.get("previousReportedAt")).isEqualTo("2026-11-14T00:10:00Z");
        // Same sampled notes on another poll give the same last-run change;
        // they do not become new evidence simply because the scheduler ran.
        var again = UpstoxWorkerReportReader.summarize(sample, NOW);
        assertThat(again.details()).isEqualTo(result.details());
    }

    @Test
    void noChangeBetweenNotesIsVisible() throws Exception {
        var sample = JSON.readTree("""
          [
            {"user":{"login":"operationReact"},"created_at":"2026-11-14T00:10:00Z",
             "body":"MINTTICK_UPSTOX_WORKER_V1\\nworker=3\\nscope=COVERAGE_GAPS\\nresult=BLOCKED\\npr=NONE\\nhead=NONE\\nci=NOT_RUN\\nruntime=NOT_OBSERVED\\nblocker=RIGHTS_UNVERIFIED\\nnext=REVIEW"},
            {"user":{"login":"operationReact"},"created_at":"2026-11-14T00:40:00Z",
             "body":"MINTTICK_UPSTOX_WORKER_V1\\nworker=3\\nscope=COVERAGE_GAPS\\nresult=BLOCKED\\npr=NONE\\nhead=NONE\\nci=NOT_RUN\\nruntime=NOT_OBSERVED\\nblocker=RIGHTS_UNVERIFIED\\nnext=REVIEW"}
          ]""");
        var result = UpstoxWorkerReportReader.summarize(sample, NOW);
        var rows = (java.util.List<?>) result.details().get("workerReports");
        var worker = (java.util.Map<?, ?>) rows.get(2);
        assertThat(worker.get("lastRunChange")).isEqualTo("NO_NEW_DELIVERY");
    }

}
