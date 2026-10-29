package in.aitradingsystem.marketdata;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;

class MarketDataQualityServiceTest {
    private final MarketSessionCalendar calendar = mock(MarketSessionCalendar.class);
    private final MarketDataQualityRepository repository = mock(MarketDataQualityRepository.class);
    private final MarketDataQualityService service = new MarketDataQualityService(
            calendar, repository, new CandleGapDetector(), new ObjectMapper().findAndRegisterModules(), 0.0);
    private static final Instant START = Instant.parse("2026-10-29T03:45:00Z");
    private static final HistoricalCandleInterval INTERVAL = HistoricalCandleInterval.ONE_MINUTE;

    @Test
    void evaluatesAndPersistsMissingCandle() {
        when(calendar.expectedSlots(LocalDate.of(2026, 10, 29), INTERVAL))
                .thenReturn(List.of(new ExpectedCandleSlot(START, INTERVAL)));
        var result = service.evaluate(10, INTERVAL, START, START.plusSeconds(60));
        assertEquals("INCOMPLETE", result.status());
        assertEquals(1, result.missingCandles());
        verify(repository).upsertQualityResult(eq(result), anyString());
        assertFalse(service.eligibleForResearch(result));
    }

    @Test
    void failsClosedWhenNoQualitySnapshotExists() {
        assertFalse(service.eligibleForResearch(10, INTERVAL));
        when(repository.findLatest(10, INTERVAL)).thenReturn("HEALTHY");
        assertTrue(service.eligibleForResearch(10, INTERVAL));
    }

    @Test
    void rejectsUnboundedAndInvalidWindows() {
        assertThrows(IllegalArgumentException.class, () -> service.evaluate(1, INTERVAL,
                START, START.plusSeconds(371L * 86400)));
        assertThrows(IllegalArgumentException.class, () -> service.evaluate(0, INTERVAL,
                START, START.plusSeconds(60)));
    }
}
