package in.aitradingsystem.scanner;

import in.aitradingsystem.instrument.Instrument;
import in.aitradingsystem.marketdata.HistoricalCandleInterval;
import in.aitradingsystem.marketdata.MarketCandle;
import in.aitradingsystem.marketdata.MarketDataQualityService;
import in.aitradingsystem.marketdata.MarketDataRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
public class ResearchScanService {
    private static final String STRATEGY_SET = String.join(",",
            VolumeBreakoutStrategy.NAME,
            MovingAverageReclaimStrategy.NAME,
            OpeningRangeBreakoutStrategy.NAME,
            VwapMomentumStrategy.NAME,
            RsiReversalStrategy.NAME);
    private static final int REQUIRED_CANDLES = 64;

    private final ResearchScanRepository scans;
    private final MarketDataRepository marketData;
    private final MarketDataQualityService quality;
    private final Clock clock;
    private final int maxUniverseSize;
    private final ScannerEngine engine;

    @Autowired
    public ResearchScanService(
            ResearchScanRepository scans,
            MarketDataRepository marketData,
            MarketDataQualityService quality,
            Clock clock,
            @Value("${app.research.scan.max-universe-size:200}") int maxUniverseSize,
            @Value("${app.research.scan.max-candle-age-minutes:60}") int maxCandleAgeMinutes) {
        this(scans, marketData, quality, clock, maxUniverseSize, maxCandleAgeMinutes, false);
    }

    /** Unit-fixture constructor: production always injects the quality gate. */
    ResearchScanService(ResearchScanRepository scans, MarketDataRepository marketData, Clock clock,
            int maxUniverseSize, int maxCandleAgeMinutes) {
        this(scans, marketData, null, clock, maxUniverseSize, maxCandleAgeMinutes, true);
    }

    private ResearchScanService(ResearchScanRepository scans, MarketDataRepository marketData,
            MarketDataQualityService quality, Clock clock, int maxUniverseSize, int maxCandleAgeMinutes,
            boolean fixtureBypass) {
        if (maxUniverseSize < 1 || maxUniverseSize > 500) {
            throw new IllegalArgumentException("maxUniverseSize must be between 1 and 500");
        }
        if (maxCandleAgeMinutes < 1 || maxCandleAgeMinutes > 1440) {
            throw new IllegalArgumentException("maxCandleAgeMinutes must be between 1 and 1440");
        }
        if (!fixtureBypass && quality == null) throw new IllegalArgumentException("market data quality gate is required");
        this.scans = scans;
        this.marketData = marketData;
        this.quality = quality;
        this.clock = clock;
        this.maxUniverseSize = maxUniverseSize;
        this.engine = new ScannerEngine(
                List.of(
                        new VolumeBreakoutStrategy(),
                        new MovingAverageReclaimStrategy(),
                        new OpeningRangeBreakoutStrategy(),
                        new VwapMomentumStrategy(),
                        new RsiReversalStrategy()),
                Duration.ofMinutes(maxCandleAgeMinutes));
    }

    public ResearchScanSummary run(HistoricalCandleInterval interval, int requestedLimit) {
        if (interval == null) throw new IllegalArgumentException("A supported interval is required");
        if (requestedLimit < 1 || requestedLimit > maxUniverseSize) {
            throw new IllegalArgumentException("requestedLimit must be between 1 and " + maxUniverseSize);
        }
        if (interval == HistoricalCandleInterval.ONE_DAY) {
            throw new IllegalArgumentException("Intraday research scans do not support ONE_DAY");
        }

        UUID runId = UUID.randomUUID();
        scans.createRun(runId, interval.name(), requestedLimit, STRATEGY_SET);
        int scannedCount = 0;
        int candidateCount = 0;
        try {
            List<Instrument> universe = scans.activeCashEquityUniverse(requestedLimit);
            Instant evaluatedAt = clock.instant();
            for (Instrument instrument : universe) {
                List<MarketCandle> candles = marketData.recentCandles(instrument.id(), interval.name(), REQUIRED_CANDLES);
                if (quality != null && (candles.isEmpty()
                        || !quality.eligibleForResearch(quality.evaluateBars(instrument.id(), interval, candles)))) {
                    scannedCount++;
                    continue;
                }
                ScanContext context = new ScanContext(
                        instrument.id(), instrument.tradingSymbol(), instrument.exchange(), interval.name(),
                        evaluatedAt, candles);
                List<ScanCandidate> candidates = engine.scan(context);
                for (ScanCandidate candidate : candidates) {
                    scans.saveCandidate(runId, candidate);
                    candidateCount++;
                }
                scannedCount++;
            }
            scans.finishRun(runId, scannedCount, candidateCount);
        } catch (RuntimeException e) {
            scans.failRun(runId, scannedCount, candidateCount, "SCAN_FAILED", "Research scan failed before completion");
        }
        return scans.requireRun(runId);
    }
}
