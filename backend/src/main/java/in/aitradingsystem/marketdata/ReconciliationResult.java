package in.aitradingsystem.marketdata;

import java.time.Instant;
import java.util.List;

public record ReconciliationResult(
        long instrumentId,
        HistoricalCandleInterval interval,
        Instant from,
        Instant to,
        String qualityStatus,
        int missingCandles,
        List<GapWindow> gaps,
        boolean autoBackfillEnabled,
        int backfillsStarted) {
}
