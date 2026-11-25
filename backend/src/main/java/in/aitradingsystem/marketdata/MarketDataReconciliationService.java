package in.aitradingsystem.marketdata;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
public class MarketDataReconciliationService {
    private final MarketDataQualityService quality;
    private final HistoricalCandleService historical;
    private final boolean autoBackfill;
    private final int maxBackfillsPerReconciliation;

    public MarketDataReconciliationService(MarketDataQualityService quality, HistoricalCandleService historical,
            @Value("${marketdata.reconciliation.auto-backfill:false}") boolean autoBackfill,
            @Value("${marketdata.reconciliation.max-backfills-per-run:5}") int maxBackfillsPerReconciliation) {
        if (maxBackfillsPerReconciliation < 1 || maxBackfillsPerReconciliation > 50) {
            throw new IllegalArgumentException("max backfills per reconciliation must be between 1 and 50");
        }
        this.quality = quality;
        this.historical = historical;
        this.autoBackfill = autoBackfill;
        this.maxBackfillsPerReconciliation = maxBackfillsPerReconciliation;
    }

    public ReconciliationResult reconcile(long instrumentId, String intervalName, Instant from, Instant to) {
        HistoricalCandleInterval interval = parse(intervalName);
        MarketDataQualityResult result = quality.evaluate(instrumentId, interval, from, to);
        List<GapWindow> gaps = findMissingWindows(result, interval);
        int started = 0;
        if (autoBackfill) {
            for (GapWindow gap : gaps) {
                if (started >= maxBackfillsPerReconciliation) break;
                LocalDate fromDate = gap.from().atZone(IndianCashMarketCalendar.ZONE).toLocalDate();
                LocalDate toDate = gap.toExclusive().minusNanos(1).atZone(IndianCashMarketCalendar.ZONE).toLocalDate();
                historical.start(instrumentId, interval, fromDate, toDate);
                started++;
            }
        }
        return new ReconciliationResult(instrumentId, interval, from, to, result.status(),
                result.missingCandles(), gaps, autoBackfill, started);
    }

    public List<GapWindow> findMissingWindows(MarketDataQualityResult result, HistoricalCandleInterval interval) {
        if (result == null || interval == null || result.gapRanges() == null || result.gapRanges().isEmpty()) {
            return List.of();
        }
        Duration step = step(interval);
        List<MarketDataQualityResult.GapRange> sorted = result.gapRanges().stream()
                .sorted(java.util.Comparator.comparing(MarketDataQualityResult.GapRange::from)).toList();
        List<GapWindow> windows = new ArrayList<>();
        Instant start = sorted.getFirst().from();
        Instant last = sorted.getFirst().from();
        int missing = 1;
        for (int i = 1; i < sorted.size(); i++) {
            Instant next = sorted.get(i).from();
            if (next.equals(last.plus(step))) {
                last = next;
                missing++;
            } else {
                windows.add(new GapWindow(start, last.plus(step), missing));
                start = next;
                last = next;
                missing = 1;
            }
        }
        windows.add(new GapWindow(start, last.plus(step), missing));
        return List.copyOf(windows);
    }

    private static HistoricalCandleInterval parse(String intervalName) {
        if (intervalName == null || intervalName.isBlank()) throw new IllegalArgumentException("interval is required");
        try {
            return HistoricalCandleInterval.valueOf(intervalName.trim().toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            throw new IllegalArgumentException("unsupported candle interval", ex);
        }
    }

    private static Duration step(HistoricalCandleInterval interval) {
        return switch (interval) {
            case ONE_MINUTE -> Duration.ofMinutes(1);
            case THREE_MINUTE -> Duration.ofMinutes(3);
            case FIVE_MINUTE -> Duration.ofMinutes(5);
            case TEN_MINUTE -> Duration.ofMinutes(10);
            case FIFTEEN_MINUTE -> Duration.ofMinutes(15);
            case THIRTY_MINUTE -> Duration.ofMinutes(30);
            case ONE_HOUR -> Duration.ofHours(1);
            case ONE_DAY -> Duration.ofDays(1);
        };
    }
}
