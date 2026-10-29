package in.aitradingsystem.strategy;

import in.aitradingsystem.instrument.Instrument;
import in.aitradingsystem.instrument.InstrumentMasterService;
import in.aitradingsystem.marketdata.HistoricalCandleInterval;
import in.aitradingsystem.marketdata.MarketCandle;
import in.aitradingsystem.marketdata.MarketDataQualityService;
import in.aitradingsystem.marketdata.MarketDataRepository;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * Explainable long-only trend/momentum strategy. Runs on completed stored candles only.
 * No network calls, AI scores, LTP lookups, or order placement happen during evaluation.
 */
@Service
public class StrategyService {
    private static final String STRATEGY = "SMA_TREND_VOLUME_V1";
    private final InstrumentMasterService instruments;
    private final MarketDataRepository candles;
    private final MarketDataQualityService quality;
    private final StrategySignalRepository signalRepository;
    private final Clock clock;
    private final int fastPeriod;
    private final int slowPeriod;
    private final BigDecimal targetRiskReward;
    private final BigDecimal stopBufferPercent;
    private final int maxSignalAgeMinutes;

    @Autowired
    public StrategyService(
            InstrumentMasterService instruments,
            MarketDataRepository candles,
            MarketDataQualityService quality,
            StrategySignalRepository signalRepository,
            Clock clock,
            @Value("${app.strategy.fast-period:5}") int fastPeriod,
            @Value("${app.strategy.slow-period:20}") int slowPeriod,
            @Value("${app.strategy.target-risk-reward:2}") BigDecimal targetRiskReward,
            @Value("${app.strategy.stop-buffer-percent:0.5}") BigDecimal stopBufferPercent,
            @Value("${app.strategy.max-signal-age-minutes:1440}") int maxSignalAgeMinutes) {
        this(instruments, candles, quality, signalRepository, clock, fastPeriod, slowPeriod,
                targetRiskReward, stopBufferPercent, maxSignalAgeMinutes, false);
    }

    /** Unit-fixture constructor: production always injects the market-data quality gate. */
    StrategyService(InstrumentMasterService instruments, MarketDataRepository candles,
            StrategySignalRepository signalRepository, Clock clock, int fastPeriod, int slowPeriod,
            BigDecimal targetRiskReward, BigDecimal stopBufferPercent, int maxSignalAgeMinutes) {
        this(instruments, candles, null, signalRepository, clock, fastPeriod, slowPeriod,
                targetRiskReward, stopBufferPercent, maxSignalAgeMinutes, true);
    }

    private StrategyService(InstrumentMasterService instruments, MarketDataRepository candles,
            MarketDataQualityService quality, StrategySignalRepository signalRepository, Clock clock,
            int fastPeriod, int slowPeriod, BigDecimal targetRiskReward, BigDecimal stopBufferPercent,
            int maxSignalAgeMinutes, boolean fixtureBypass) {
        if (fastPeriod < 2 || slowPeriod <= fastPeriod || slowPeriod > 500) {
            throw new IllegalArgumentException("Strategy periods must satisfy 2 <= fastPeriod < slowPeriod <= 500");
        }
        if (targetRiskReward == null || targetRiskReward.compareTo(BigDecimal.ONE) < 0
                || targetRiskReward.compareTo(new BigDecimal("5")) > 0) {
            throw new IllegalArgumentException("targetRiskReward must be between 1 and 5");
        }
        if (stopBufferPercent == null || stopBufferPercent.compareTo(BigDecimal.ZERO) <= 0
                || stopBufferPercent.compareTo(new BigDecimal("10")) > 0) {
            throw new IllegalArgumentException("stopBufferPercent must be greater than 0 and at most 10");
        }
        if (maxSignalAgeMinutes < 1 || maxSignalAgeMinutes > 10080) {
            throw new IllegalArgumentException("maxSignalAgeMinutes must be between 1 and 10080");
        }
        if (!fixtureBypass && quality == null) throw new IllegalArgumentException("market data quality gate is required");
        this.instruments = instruments;
        this.candles = candles;
        this.quality = quality;
        this.signalRepository = signalRepository;
        this.clock = clock;
        this.fastPeriod = fastPeriod;
        this.slowPeriod = slowPeriod;
        this.targetRiskReward = targetRiskReward;
        this.stopBufferPercent = stopBufferPercent;
        this.maxSignalAgeMinutes = maxSignalAgeMinutes;
    }

    public StrategyEvaluation evaluate(long instrumentId, HistoricalCandleInterval interval) {
        if (interval == null) throw new IllegalArgumentException("A supported interval is required");
        Instrument instrument = instruments.requireActiveInstrument(instrumentId);
        int required = slowPeriod + 1;
        int available = candles.countCandles(instrumentId, interval.name());
        List<MarketCandle> bars = candles.recentCandles(instrumentId, interval.name(), required);
        Instant now = clock.instant();
        Instant latest = bars.isEmpty() ? null : bars.getLast().candleTime();
        List<String> reasons = new ArrayList<>();
        StrategySignal signal = null;

        if (available < required || bars.size() < required) {
            reasons.add("INSUFFICIENT_HISTORY");
        } else if (quality != null && !quality.eligibleForResearch(quality.evaluateBars(instrumentId, interval, bars))) {
            reasons.add("DATA_QUALITY_NOT_ELIGIBLE");
        } else {
            MarketCandle last = bars.getLast();
            boolean stale = Duration.between(last.candleTime(), now).toMinutes() > maxSignalAgeMinutes
                    || last.candleTime().isAfter(now.plusSeconds(30));
            BigDecimal close = last.close();
            BigDecimal fast = average(bars, bars.size() - fastPeriod, bars.size());
            BigDecimal slow = average(bars, bars.size() - slowPeriod, bars.size());
            BigDecimal volumeAvg = averageVolume(bars, bars.size() - 1 - slowPeriod, bars.size() - 1);
            BigDecimal latestVolume = BigDecimal.valueOf(last.volume());
            boolean volumeConfirmed = volumeAvg.signum() > 0 && latestVolume.compareTo(volumeAvg) >= 0;
            String action = "WATCH";
            reasons.add("CLOSE_PRICE_ABOVE_ZERO");
            if (stale) {
                action = "WATCH";
                reasons.add("STALE_OR_FUTURE_CANDLE");
            } else if (fast.compareTo(slow) > 0 && close.compareTo(slow) > 0 && volumeConfirmed) {
                action = "BUY";
                reasons.add("FAST_SMA_ABOVE_SLOW_SMA");
                reasons.add("CLOSE_ABOVE_SLOW_SMA");
                reasons.add("VOLUME_CONFIRMED");
            } else {
                if (fast.compareTo(slow) <= 0) reasons.add("TREND_NOT_BULLISH");
                if (close.compareTo(slow) <= 0) reasons.add("CLOSE_NOT_ABOVE_SLOW_SMA");
                if (!volumeConfirmed) reasons.add("VOLUME_NOT_CONFIRMED");
            }

            BigDecimal stop = BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
            BigDecimal target = BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
            if ("BUY".equals(action)) {
                BigDecimal buffer = close.multiply(stopBufferPercent).divide(new BigDecimal("100"), 8, RoundingMode.HALF_UP);
                stop = close.subtract(buffer).setScale(2, RoundingMode.HALF_UP);
                if (stop.signum() <= 0 || stop.compareTo(close) >= 0) {
                    action = "WATCH";
                    stop = BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
                    reasons.add("INVALID_STOP_LEVEL");
                } else {
                    target = close.add(close.subtract(stop).multiply(targetRiskReward)).setScale(2, RoundingMode.HALF_UP);
                }
            }
            double confidence = confidence(fast, slow, volumeConfirmed, stale);
            signal = new StrategySignal(
                    UUID.nameUUIDFromBytes((instrument.id() + ":" + instrument.exchange() + ":" + instrument.symbolToken() + ":" + interval.name()
                            + ":" + last.candleTime() + ":" + STRATEGY).getBytes(java.nio.charset.StandardCharsets.UTF_8)).toString(),
                    instrument.id(), instrument.tradingSymbol(), instrument.exchange(), interval.name(), action, STRATEGY,
                    close, stop, target, fast, slow, volumeAvg, latestVolume, confidence, List.copyOf(reasons),
                    last.source(), now, last.candleTime(), "CANDIDATE_NOT_EXECUTABLE");
            signalRepository.save(signal, instrument.symbolToken());
        }
        return new StrategyEvaluation(instrument.id(), instrument.tradingSymbol(), instrument.exchange(), interval.name(),
                required, available, latest, signal, List.copyOf(reasons), now,
                bars.isEmpty() ? "NO_STORED_CANDLES" : bars.getLast().source());
    }

    private BigDecimal average(List<MarketCandle> bars, int start, int endExclusive) {
        BigDecimal total = BigDecimal.ZERO;
        for (int i = start; i < endExclusive; i++) total = total.add(bars.get(i).close());
        return total.divide(BigDecimal.valueOf(endExclusive - start), 6, RoundingMode.HALF_UP);
    }

    private BigDecimal averageVolume(List<MarketCandle> bars, int start, int endExclusive) {
        BigDecimal total = BigDecimal.ZERO;
        for (int i = start; i < endExclusive; i++) total = total.add(BigDecimal.valueOf(bars.get(i).volume()));
        if (endExclusive <= start) return BigDecimal.ZERO;
        return total.divide(BigDecimal.valueOf(endExclusive - start), 2, RoundingMode.HALF_UP);
    }

    private double confidence(BigDecimal fast, BigDecimal slow, boolean volumeConfirmed, boolean stale) {
        if (stale) return 0.0;
        if (fast.compareTo(slow) <= 0) return 0.25;
        BigDecimal trend = fast.subtract(slow).abs().divide(slow.abs().max(new BigDecimal("0.01")), 6, RoundingMode.HALF_UP);
        double score = 0.50 + Math.min(0.25, trend.doubleValue() * 5.0) + (volumeConfirmed ? 0.15 : 0.0);
        return Math.min(0.90, Math.max(0.0, score));
    }
}
