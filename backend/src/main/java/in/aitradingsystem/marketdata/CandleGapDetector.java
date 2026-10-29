package in.aitradingsystem.marketdata;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/** Compares actual timestamps against expected exchange slots; never interpolates market prices. */
@Component
public class CandleGapDetector {
    public MarketDataQualityResult detect(long instrumentId, HistoricalCandleInterval interval,
            Instant from, Instant to, List<Instant> actualTimes, List<ExpectedCandleSlot> expectedSlots,
            int zeroVolumeCandles) {
        if (from == null || to == null || !to.isAfter(from) || interval == null)
            throw new IllegalArgumentException("Invalid quality evaluation window");
        Map<Instant, Integer> counts = new HashMap<>();
        for (Instant t : actualTimes) {
            if (t != null && !t.isBefore(from) && t.isBefore(to)) counts.merge(t, 1, Integer::sum);
        }
        int duplicates = counts.values().stream().mapToInt(n -> Math.max(0, n - 1)).sum();
        List<Instant> missing = new ArrayList<>();
        for (ExpectedCandleSlot slot : expectedSlots) {
            if (!slot.timestamp().isBefore(from) && slot.timestamp().isBefore(to)
                    && !counts.containsKey(slot.timestamp())) missing.add(slot.timestamp());
        }
        List<MarketDataQualityResult.GapRange> gaps = new ArrayList<>();
        for (Instant t : missing) gaps.add(new MarketDataQualityResult.GapRange(t, t));
        int expected = (int) expectedSlots.stream().filter(s ->
                !s.timestamp().isBefore(from) && s.timestamp().isBefore(to)).count();
        String status = missing.isEmpty() && duplicates == 0 && zeroVolumeCandles == 0
                ? "HEALTHY" : (expected > 0 && missing.size() == expected ? "INCOMPLETE" : "DEGRADED");
        return new MarketDataQualityResult(instrumentId, interval, from, to, expected,
                counts.size(), missing.size(), duplicates, zeroVolumeCandles, status, List.copyOf(gaps));
    }
}
