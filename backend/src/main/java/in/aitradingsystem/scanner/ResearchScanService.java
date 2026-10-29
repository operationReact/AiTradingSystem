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
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
public class ResearchScanService {
    private static final int REQUIRED_CANDLES = 64;

    private final ResearchScanRepository scans;
    private final MarketDataRepository marketData;
    private final MarketDataQualityService quality;
    private final Clock clock;
    private final int maxUniverseSize;
    private final StrategyRegistry registry;
    private final StrategyConfigService strategyConfigs;
    private final ScannerEngine engine;

    @Autowired
    public ResearchScanService(
            ResearchScanRepository scans,
            MarketDataRepository marketData,
            MarketDataQualityService quality,
            Clock clock,
            StrategyRegistry registry,
            StrategyConfigService strategyConfigs,
            @Value("${app.research.scan.max-universe-size:200}") int maxUniverseSize,
            @Value("${app.research.scan.max-candle-age-minutes:60}") int maxCandleAgeMinutes) {
        this(scans, marketData, quality, clock, registry, strategyConfigs,
                maxUniverseSize, maxCandleAgeMinutes, false);
    }

    /** Unit-fixture constructor: production always injects the quality/configuration gates. */
    ResearchScanService(ResearchScanRepository scans, MarketDataRepository marketData, Clock clock,
            int maxUniverseSize, int maxCandleAgeMinutes) {
        this(scans, marketData, null, clock, defaultRegistry(), null,
                maxUniverseSize, maxCandleAgeMinutes, true);
    }

    /** Quality-gate fixture constructor retained for focused scan tests. */
    ResearchScanService(ResearchScanRepository scans, MarketDataRepository marketData,
            MarketDataQualityService quality, Clock clock, int maxUniverseSize, int maxCandleAgeMinutes) {
        this(scans, marketData, quality, clock, defaultRegistry(), null,
                maxUniverseSize, maxCandleAgeMinutes, true);
    }

    private ResearchScanService(ResearchScanRepository scans, MarketDataRepository marketData,
            MarketDataQualityService quality, Clock clock, StrategyRegistry registry,
            StrategyConfigService strategyConfigs, int maxUniverseSize, int maxCandleAgeMinutes,
            boolean fixtureBypass) {
        if (maxUniverseSize < 1 || maxUniverseSize > 500) {
            throw new IllegalArgumentException("maxUniverseSize must be between 1 and 500");
        }
        if (maxCandleAgeMinutes < 1 || maxCandleAgeMinutes > 1440) {
            throw new IllegalArgumentException("maxCandleAgeMinutes must be between 1 and 1440");
        }
        if (registry == null) throw new IllegalArgumentException("strategy registry is required");
        if (!fixtureBypass && quality == null) throw new IllegalArgumentException("market data quality gate is required");
        if (!fixtureBypass && strategyConfigs == null) throw new IllegalArgumentException("strategy config service is required");
        this.scans = scans;
        this.marketData = marketData;
        this.quality = quality;
        this.clock = clock;
        this.maxUniverseSize = maxUniverseSize;
        this.registry = registry;
        this.strategyConfigs = strategyConfigs;
        this.engine = new ScannerEngine(registry.enabledStrategies(), Duration.ofMinutes(maxCandleAgeMinutes));
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
        String strategySet = registry.descriptors().stream()
                .map(StrategyDescriptor::code)
                .collect(Collectors.joining(","));
        scans.createRun(runId, interval.name(), requestedLimit, strategySet);
        int scannedCount = 0;
        int candidateCount = 0;
        int diagnosticCount = 0;
        try {
            List<Instrument> universe = scans.activeCashEquityUniverse(requestedLimit);
            Instant evaluatedAt = clock.instant();
            for (Instrument instrument : universe) {
                try {
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
                    Instant dataCutoffAt = candles.isEmpty() ? null : candles.getLast().candleTime();
                    for (ScanCandidate candidate : candidates) {
                        ScannerStrategy strategy = registry.require(candidate.strategy())
                                .orElseThrow(() -> new IllegalStateException("Candidate references unknown strategy: " + candidate.strategy()));
                        StrategyConfigSnapshot activeConfig = strategyConfigs == null ? null : strategyConfigs.active(candidate.strategy());
                        Integer configVersion = activeConfig == null ? null : activeConfig.configVersion();
                        if (scans.saveCandidate(runId, candidate, strategy.descriptor().version(), configVersion,
                                instrument.catalogueVersion(), dataCutoffAt)) {
                            candidateCount++;
                        }
                    }
                    scannedCount++;
                } catch (RuntimeException instrumentFailure) {
                    diagnosticCount++;
                    scans.saveDiagnostic(runId, instrument.id(), null, "ERROR", "INSTRUMENT_SCAN_FAILED",
                            "Instrument scan failed; remaining universe continued");
                }
            }
            if (diagnosticCount == 0) {
                scans.finishRun(runId, scannedCount, candidateCount);
            } else {
                scans.partialRun(runId, scannedCount, candidateCount, "PARTIAL_SCAN",
                        diagnosticCount + " instrument(s) failed during scan");
            }
        } catch (RuntimeException e) {
            scans.saveDiagnostic(runId, null, null, "ERROR", "SCAN_FAILED",
                    "Research scan failed before the universe could be completed");
            scans.failRun(runId, scannedCount, candidateCount, "SCAN_FAILED", "Research scan failed before completion");
        }
        return scans.requireRun(runId);
    }

    private static StrategyRegistry defaultRegistry() {
        return new StrategyRegistry(List.of(
                new VolumeBreakoutStrategy(),
                new MovingAverageReclaimStrategy(),
                new OpeningRangeBreakoutStrategy(),
                new VwapMomentumStrategy(),
                new RsiReversalStrategy()));
    }
}
