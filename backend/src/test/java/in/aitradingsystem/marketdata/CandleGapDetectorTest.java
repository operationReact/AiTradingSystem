package in.aitradingsystem.marketdata;

import static org.junit.jupiter.api.Assertions.*;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class CandleGapDetectorTest {
    private final CandleGapDetector detector = new CandleGapDetector();
    private static final HistoricalCandleInterval INTERVAL = HistoricalCandleInterval.ONE_MINUTE;
    private static final Instant FROM = Instant.parse("2026-10-29T03:45:00Z");
    private static final Instant TO = FROM.plusSeconds(180);

    @Test
    void detectsMissingSlotsWithoutInventingCandles() {
        var expected = List.of(new ExpectedCandleSlot(FROM, INTERVAL),
                new ExpectedCandleSlot(FROM.plusSeconds(60), INTERVAL),
                new ExpectedCandleSlot(FROM.plusSeconds(120), INTERVAL));
        var result = detector.detect(1, INTERVAL, FROM, TO,
                List.of(FROM, FROM.plusSeconds(120)), expected, 0);
        assertEquals(3, result.expectedCandles());
        assertEquals(2, result.actualCandles());
        assertEquals(1, result.missingCandles());
        assertEquals("DEGRADED", result.status());
        assertEquals(FROM.plusSeconds(60), result.gapRanges().get(0).from());
    }

    @Test
    void detectsDuplicatesAndZeroVolume() {
        var expected = List.of(new ExpectedCandleSlot(FROM, INTERVAL));
        var result = detector.detect(1, INTERVAL, FROM, TO, List.of(FROM, FROM), expected, 1);
        assertEquals(1, result.duplicateCandles());
        assertEquals(1, result.zeroVolumeCandles());
        assertEquals("DEGRADED", result.status());
    }

    @Test
    void missingEveryExpectedCandleIsIncomplete() {
        var result = detector.detect(1, INTERVAL, FROM, TO, List.of(),
                List.of(new ExpectedCandleSlot(FROM, INTERVAL)), 0);
        assertEquals("INCOMPLETE", result.status());
    }
}
