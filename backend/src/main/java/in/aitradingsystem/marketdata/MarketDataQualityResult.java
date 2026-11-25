package in.aitradingsystem.marketdata;

import java.time.Instant;
import java.util.List;

public record MarketDataQualityResult(
        long instrumentId, HistoricalCandleInterval interval, Instant from, Instant to,
        int expectedCandles, int actualCandles, int missingCandles, int duplicateCandles,
        int zeroVolumeCandles, String status, List<GapRange> gapRanges) {
    public record GapRange(Instant from, Instant to) {}
}
