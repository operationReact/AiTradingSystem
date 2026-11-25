package in.aitradingsystem.provider.upstox;

import static org.junit.jupiter.api.Assertions.*;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;

class UpstoxHistoryWindowPlannerTest {
    private static final String KEY = "NSE_EQ|INE002A01018";

    @Test void partitionsDailyWithoutOverlapsOrGaps() {
        var windows = UpstoxHistoryWindowPlanner.plan(KEY, "days", 1,
                LocalDate.parse("2023-01-01"), LocalDate.parse("2025-12-31"),
                LocalDate.parse("2026-01-01"), 10);
        assertEquals(3, windows.size());
        assertEquals(1096, UpstoxHistoryWindowPlanner.coveredCalendarDays(windows));
        for (int i = 1; i < windows.size(); i++) {
            assertEquals(windows.get(i - 1).through().plusDays(1), windows.get(i).from());
        }
        assertTrue(windows.get(0).checkpointId().startsWith("UPSTOX_ANALYTICS:"));
    }

    @Test void rejectsBudgetOverrunAndUnsupportedOrFutureRequests() {
        assertThrows(IllegalArgumentException.class, () -> UpstoxHistoryWindowPlanner.plan(KEY,
                "minutes", 1, LocalDate.parse("2026-01-01"), LocalDate.parse("2026-01-10"),
                LocalDate.parse("2026-01-10"), 2));
        assertThrows(IllegalArgumentException.class, () -> UpstoxHistoryWindowPlanner.plan(KEY,
                "seconds", 1, LocalDate.parse("2026-01-01"), LocalDate.parse("2026-01-01"),
                LocalDate.parse("2026-01-10"), 1));
        assertThrows(IllegalArgumentException.class, () -> UpstoxHistoryWindowPlanner.plan(KEY,
                "days", 1, LocalDate.parse("2026-01-01"), LocalDate.parse("2026-01-11"),
                LocalDate.parse("2026-01-10"), 1));
        assertThrows(IllegalArgumentException.class, () -> UpstoxHistoryWindowPlanner.plan(
                "NSE_EQ|123", "days", 1, LocalDate.parse("2026-01-01"),
                LocalDate.parse("2026-01-01"), LocalDate.parse("2026-01-10"), 1));
    }

    @Test void sourceCheckpointIsDeterministicAndUnmodifiable() {
        var a = UpstoxHistoryWindowPlanner.plan(KEY, "hours", 1,
                LocalDate.parse("2026-01-01"), LocalDate.parse("2026-02-15"),
                LocalDate.parse("2026-02-15"), 3);
        var b = UpstoxHistoryWindowPlanner.plan(KEY, "hours", 1,
                LocalDate.parse("2026-01-01"), LocalDate.parse("2026-02-15"),
                LocalDate.parse("2026-02-15"), 3);
        assertEquals(a, b);
        assertEquals(2, a.size());
        assertThrows(UnsupportedOperationException.class, () -> a.clear());
    }
}
