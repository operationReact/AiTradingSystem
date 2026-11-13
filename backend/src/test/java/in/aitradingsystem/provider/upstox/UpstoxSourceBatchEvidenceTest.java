package in.aitradingsystem.provider.upstox;

import static org.junit.jupiter.api.Assertions.*;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;

class UpstoxSourceBatchEvidenceTest {
    private static final Instant NOW = Instant.parse("2026-11-13T12:00:00Z");
    private static List<UpstoxHistoryWindowPlanner.Window> windows() {
        return UpstoxHistoryWindowPlanner.plan("NSE_EQ|INE002A01018", "minutes", 1,
                LocalDate.parse("2026-01-01"), LocalDate.parse("2026-01-04"),
                LocalDate.parse("2026-01-05"), 4);
    }

    @Test void distinguishesObservedZeroFromNoRun() {
        var result = UpstoxSourceBatchEvidence.read(windows(), w -> List.of(), NOW);
        assertEquals(2, result.attempted());
        assertEquals(2, result.succeeded());
        assertEquals(0, result.failed());
        assertTrue(result.complete());
        assertEquals(0, result.observations().get(0).returnedCandles());
        assertEquals("UPSTOX_ANALYTICS", result.observations().get(0).source());
        assertEquals(NOW, result.observations().get(0).observedAt());
        assertThrows(IllegalArgumentException.class,
                () -> UpstoxSourceBatchEvidence.read(List.of(), w -> List.of(), NOW));
    }

    @Test void stopsOnFailureWithoutClaimingCompletionOrLeakingUpstreamMessage() {
        var result = UpstoxSourceBatchEvidence.read(windows(), w -> {
            if (w.from().isAfter(LocalDate.parse("2026-01-01")))
                throw new IllegalStateException("private credential must never appear");
            return List.of();
        }, NOW);
        assertEquals(2, result.attempted());
        assertEquals(1, result.succeeded());
        assertEquals(1, result.failed());
        assertFalse(result.complete());
        assertEquals("UPSTOX_SOURCE_WINDOW_FAILED", result.failureCode());
        assertFalse(result.toString().contains("private credential"));
        assertEquals(1, result.observations().size());
    }

    @Test void enforcesMaximumBatchBeforeAnyProviderRead() {
        var calls = new java.util.concurrent.atomic.AtomicInteger();
        var oversized = java.util.Collections.nCopies(101, windows().get(0));
        assertThrows(IllegalArgumentException.class,
                () -> UpstoxSourceBatchEvidence.read(oversized, w -> {
                    calls.incrementAndGet();
                    return List.of();
                }, NOW));
        assertEquals(0, calls.get());
    }

    @Test void rejectsNullAndOversizedResponse() {
        assertFalse(UpstoxSourceBatchEvidence.read(windows(), w -> null, NOW).complete());
        assertThrows(NullPointerException.class,
                () -> UpstoxSourceBatchEvidence.read(windows(), w -> List.of(), null));
    }
}
