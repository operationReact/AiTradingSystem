package in.aitradingsystem.provider.upstox;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;

/**
 * Pure source-side planning; no token access, HTTP, persistence or permission to retain data.
 * Each immutable window is independently checkpointable by the canonical dataset worker.
 */
public final class UpstoxHistoryWindowPlanner {
    private UpstoxHistoryWindowPlanner() {}

    public record Window(String instrumentKey, String unit, int interval,
                         LocalDate from, LocalDate through, String checkpointId) {}

    public static List<Window> plan(String instrumentKey, String unit, int interval,
                                    LocalDate from, LocalDate through, LocalDate asOf,
                                    int maxWindows) {
        long maxDays = UpstoxAnalyticsSource.allowedWindowDays(unit, interval);
        if (instrumentKey == null || !instrumentKey.matches("(NSE_EQ|BSE_EQ)\\|[A-Z]{2}[A-Z0-9]{10}")
                || maxDays < 0 || from == null || through == null || asOf == null
                || from.isAfter(through) || through.isAfter(asOf)
                || maxWindows < 1 || maxWindows > 100) {
            throw new IllegalArgumentException("Invalid Upstox source window plan");
        }
        List<Window> windows = new ArrayList<>();
        LocalDate cursor = from;
        while (!cursor.isAfter(through)) {
            if (windows.size() == maxWindows) {
                throw new IllegalArgumentException("Upstox window budget exceeded");
            }
            LocalDate end = cursor.plusDays(maxDays);
            if (end.isAfter(through)) end = through;
            // Stable, source-scoped checkpoint identity; never use provider key as canonical listing identity.
            String id = "UPSTOX_ANALYTICS:" + instrumentKey + ":" + unit + ":" + interval
                    + ":" + cursor + ":" + end;
            windows.add(new Window(instrumentKey, unit, interval, cursor, end, id));
            cursor = end.plusDays(1);
        }
        return List.copyOf(windows);
    }

    public static long coveredCalendarDays(List<Window> windows) {
        return windows.stream().mapToLong(w -> ChronoUnit.DAYS.between(w.from(), w.through()) + 1).sum();
    }
}
