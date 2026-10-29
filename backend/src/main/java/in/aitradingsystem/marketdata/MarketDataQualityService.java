package in.aitradingsystem.marketdata;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
public class MarketDataQualityService {
    private final MarketSessionCalendar calendar;
    private final MarketDataQualityRepository repository;
    private final CandleGapDetector detector;
    private final ObjectMapper mapper;
    private final double maximumMissingPercent;

    public MarketDataQualityService(MarketSessionCalendar calendar, MarketDataQualityRepository repository,
            CandleGapDetector detector, ObjectMapper mapper,
            @Value("${marketdata.quality.maximum-missing-percent:0.0}") double maximumMissingPercent) {
        this.calendar = calendar;
        this.repository = repository;
        this.detector = detector;
        this.mapper = mapper;
        if (maximumMissingPercent < 0 || maximumMissingPercent > 100)
            throw new IllegalArgumentException("maximum missing percentage must be between 0 and 100");
        this.maximumMissingPercent = maximumMissingPercent;
    }

    public MarketDataQualityResult evaluate(long instrumentId, HistoricalCandleInterval interval, Instant from, Instant to) {
        if (instrumentId <= 0 || interval == null || from == null || to == null || !to.isAfter(from))
            throw new IllegalArgumentException("Invalid candle quality request");
        if (Duration.between(from, to).toDays() > 370)
            throw new IllegalArgumentException("Quality window exceeds 370 days");
        LocalDate first = from.atZone(IndianCashMarketCalendar.ZONE).toLocalDate();
        LocalDate last = to.minusNanos(1).atZone(IndianCashMarketCalendar.ZONE).toLocalDate();
        List<ExpectedCandleSlot> expected = new ArrayList<>();
        for (LocalDate day = first; !day.isAfter(last); day = day.plusDays(1)) {
            expected.addAll(calendar.expectedSlots(day, interval));
        }
        var actual = repository.candleTimes(instrumentId, interval, from, to);
        int zeroVolume = repository.zeroVolumeCount(instrumentId, interval, from, to);
        var result = detector.detect(instrumentId, interval, from, to, actual, expected, zeroVolume);
        try {
            repository.upsertQualityResult(result, mapper.writeValueAsString(result.gapRanges()));
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("Unable to serialize market data gaps", ex);
        }
        return result;
    }

    public MarketDataQualityResult evaluateBars(long instrumentId, HistoricalCandleInterval interval,
            List<MarketCandle> bars) {
        if (bars == null || bars.isEmpty()) {
            throw new IllegalArgumentException("Stored candles are required for quality evaluation");
        }
        Instant from = bars.getFirst().candleTime();
        Instant to = bars.getLast().candleTime().plus(step(interval));
        return evaluate(instrumentId, interval, from, to);
    }

    public String latest(long instrumentId, HistoricalCandleInterval interval) {
        return repository.findLatest(instrumentId, interval);
    }

    public boolean eligibleForResearch(long instrumentId, HistoricalCandleInterval interval) {
        String status = latest(instrumentId, interval);
        return "HEALTHY".equals(status);
    }

    public boolean eligibleForResearch(MarketDataQualityResult result) {
        if (result == null || result.expectedCandles() == 0 || result.duplicateCandles() > 0
                || result.zeroVolumeCandles() > 0 || "FAILED".equals(result.status())) return false;
        return result.missingCandles() * 100.0 / result.expectedCandles() <= maximumMissingPercent;
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
