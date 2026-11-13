package in.aitradingsystem.provider.upstox;

import org.junit.jupiter.api.Test;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class UpstoxWorkerReportLedgerTest {
    private static final Instant NOW = Instant.parse("2026-11-14T00:00:00Z");
    private static final Duration AGE = Duration.ofHours(2);
    private String report(int worker, String scope) {
        return String.join("\n", "MINTTICK_UPSTOX_WORKER_V1",
            "worker=" + worker, "scope=" + scope, "result=PR_OPEN",
            "pr=1015", "head=NONE", "ci=PENDING", "runtime=NOT_OBSERVED",
            "blocker=RIGHTS_UNKNOWN", "next=CHECK_CI");
    }
    @Test void acceptsCorrectWorkerAndDoesNotInferRuntime() {
        var r = UpstoxWorkerReportLedger.parse(
            new UpstoxWorkerReportLedger.Comment(report(5, "OPERATIONS_UI"), NOW), NOW, AGE);
        assertTrue(r.isPresent());
        assertEquals("NOT_OBSERVED", r.get().runtime());
        assertEquals(UpstoxWorkerReportLedger.State.FRESH, r.get().state());
    }
    @Test void rejectsSpoofedScopeAndFutureReports() {
        assertTrue(UpstoxWorkerReportLedger.parse(
            new UpstoxWorkerReportLedger.Comment(report(5, "SOURCE_INGESTION"), NOW), NOW, AGE).isEmpty());
        assertTrue(UpstoxWorkerReportLedger.parse(
            new UpstoxWorkerReportLedger.Comment(report(5, "OPERATIONS_UI"), NOW.plusSeconds(1)), NOW, AGE).isEmpty());
    }
    @Test void distinguishesNoReportFromStaleAndSelectsLatest() {
        var old = new UpstoxWorkerReportLedger.Comment(report(5, "OPERATIONS_UI"), NOW.minusSeconds(10800));
        var fresh = new UpstoxWorkerReportLedger.Comment(report(5, "OPERATIONS_UI"), NOW.minusSeconds(10));
        var latest = UpstoxWorkerReportLedger.latest(List.of(fresh, old), NOW, AGE);
        assertTrue(latest.get(1).isEmpty());
        assertEquals(UpstoxWorkerReportLedger.State.FRESH, latest.get(5).orElseThrow().state());
        assertEquals(UpstoxWorkerReportLedger.State.STALE,
            UpstoxWorkerReportLedger.latest(List.of(old), NOW, AGE).get(5).orElseThrow().state());
    }
    @Test void acceptsRealAuditCommentWithTrailingHumanNotes() {
        String body = report(3, "COVERAGE_GAPS") +
            "\\n\\nClosed superseded draft #1005 after reconciliation";
        var parsed = UpstoxWorkerReportLedger.parse(
            new UpstoxWorkerReportLedger.Comment(body, NOW), NOW, AGE);
        assertTrue(parsed.isPresent());
        assertEquals(3, parsed.orElseThrow().worker());
    }
    @Test void rejectsDuplicateFieldsAndUnstructuredText() {
        String body = report(5, "OPERATIONS_UI");
        assertTrue(UpstoxWorkerReportLedger.parse(
            new UpstoxWorkerReportLedger.Comment(body + "\nci=PASS", NOW), NOW, AGE).isEmpty());
        assertTrue(UpstoxWorkerReportLedger.parse(
            new UpstoxWorkerReportLedger.Comment("worker=5", NOW), NOW, AGE).isEmpty());
    }
}
