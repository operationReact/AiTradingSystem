package in.aitradingsystem.provider.upstox;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * Bounded, ephemeral source-read evidence. No persistence, scheduling, training rights
 * or claim of canonical listing identity. A failed window never becomes "complete".
 */
public final class UpstoxSourceBatchEvidence {
    private UpstoxSourceBatchEvidence() {}

    @FunctionalInterface
    public interface Reader {
        List<UpstoxAnalyticsSource.Candle> read(UpstoxHistoryWindowPlanner.Window window);
    }

    public record WindowObservation(String checkpointId, int returnedCandles,
                                    Instant observedAt, String source) {}
    public record BatchResult(int attempted, int succeeded, int failed,
                              List<WindowObservation> observations, String failureCode) {
        public boolean complete() { return failed == 0 && attempted == succeeded; }
    }

    public static BatchResult read(List<UpstoxHistoryWindowPlanner.Window> windows,
                                   Reader reader, Instant observedAt) {
        Objects.requireNonNull(windows, "windows");
        Objects.requireNonNull(reader, "reader");
        Objects.requireNonNull(observedAt, "observedAt");
        if (windows.isEmpty() || windows.size() > 100 || windows.stream().anyMatch(Objects::isNull))
            throw new IllegalArgumentException("Invalid bounded source batch");
        var observations = new java.util.ArrayList<WindowObservation>();
        int attempted = 0;
        for (var window : windows) {
            attempted++;
            try {
                List<UpstoxAnalyticsSource.Candle> candles = Objects.requireNonNull(reader.read(window));
                if (candles.size() > 1024 || candles.stream().anyMatch(Objects::isNull))
                    throw new IllegalStateException("UPSTOX_INVALID_CANDLES");
                observations.add(new WindowObservation(window.checkpointId(), candles.size(),
                        observedAt, "UPSTOX_ANALYTICS"));
            } catch (RuntimeException failure) {
                // Do not return upstream exception messages: may contain tokens or account data.
                return new BatchResult(attempted, observations.size(), 1,
                        List.copyOf(observations), "UPSTOX_SOURCE_WINDOW_FAILED");
            }
        }
        return new BatchResult(attempted, observations.size(), 0,
                List.copyOf(observations), null);
    }
}
