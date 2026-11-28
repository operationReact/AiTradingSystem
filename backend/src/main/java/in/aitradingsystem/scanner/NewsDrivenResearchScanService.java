package in.aitradingsystem.scanner;

import in.aitradingsystem.instrument.Instrument;
import in.aitradingsystem.marketdata.HistoricalCandleInterval;
import in.aitradingsystem.marketdata.MarketCandle;
import in.aitradingsystem.marketdata.MarketDataQualityService;
import in.aitradingsystem.marketdata.MarketDataRepository;
import in.aitradingsystem.news.feature.NewsContext;
import in.aitradingsystem.news.feature.NewsFeatureService;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/**
 * Creates research-only Mind candidates from eligible point-in-time news evidence.
 * This path is independent of technical strategy emissions and never submits orders.
 */
@Service
public class NewsDrivenResearchScanService {
    public static final String STRATEGY = "NEWS_IMPACT_V1";
    private static final int REQUIRED_CANDLES = 64;
    private static final int MAX_UNIVERSE_SIZE = 3500;
    private static final BigDecimal MIN_ABSOLUTE_DIRECTION = new BigDecimal("0.50");

    private final ResearchScanRepository scans;
    private final MarketDataRepository marketData;
    private final MarketDataQualityService quality;
    private final NewsFeatureService news;
    private final Clock clock;

    public NewsDrivenResearchScanService(ResearchScanRepository scans, MarketDataRepository marketData,
            MarketDataQualityService quality, NewsFeatureService news, Clock clock) {
        this.scans = scans;
        this.marketData = marketData;
        this.quality = quality;
        this.news = news;
        this.clock = clock;
    }

    public ResearchScanSummary run(HistoricalCandleInterval interval, int requestedLimit) {
        if (interval == null || interval == HistoricalCandleInterval.ONE_DAY) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "A supported intraday interval is required");
        }
        if (requestedLimit < 1 || requestedLimit > MAX_UNIVERSE_SIZE) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "requestedLimit must be between 1 and " + MAX_UNIVERSE_SIZE);
        }
        if (!news.enabled()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                "News-driven Mind research is disabled until NEWS_MIND_CONTEXT_ENABLED is enabled");
        }

        UUID runId = UUID.randomUUID();
        scans.createRun(runId, interval.name(), requestedLimit, STRATEGY);
        int scanned = 0;
        int candidates = 0;
        int diagnostics = 0;
        int incompleteHistory = 0;
        try {
            List<Instrument> universe = scans.activeCashEquityUniverse(requestedLimit);
            Instant asOf = clock.instant();
            for (Instrument instrument : universe) {
                try {
                    List<MarketCandle> candles =
                        marketData.recentCandles(instrument.id(), interval.name(), REQUIRED_CANDLES);
                    if (candles.size() < REQUIRED_CANDLES) {
                        incompleteHistory++;
                        diagnostics++;
                        scans.saveDiagnostic(runId, instrument.id(), STRATEGY, "WARN",
                            "INSUFFICIENT_CANDLE_HISTORY",
                            "News direction was not converted to a Mind candidate because the 64-candle market-data gate failed");
                        scans.saveOutcome(runId, instrument.id(), STRATEGY, "NOT_EVALUATED",
                            "INSUFFICIENT_CANDLE_HISTORY", candles.isEmpty() ? null : candles.getLast().candleTime());
                        scanned++;
                        continue;
                    }
                    if (!quality.eligibleForResearch(quality.evaluateBars(instrument.id(), interval, candles))) {
                        scans.saveOutcome(runId, instrument.id(), STRATEGY, "NOT_EVALUATED",
                            "QUALITY_GATE_REJECTED", candles.getLast().candleTime());
                        scanned++;
                        continue;
                    }

                    Instant cutoff = candles.getLast().candleTime();
                    NewsContext context = news.contextFor(instrument.id(), instrument.tradingSymbol(), asOf);
                    boolean eligible = eligible(context);
                    scans.saveOutcome(runId, instrument.id(), STRATEGY,
                        eligible ? "CANDIDATE" : "NO_SIGNAL",
                        eligible ? "NEWS_DIRECTIONAL_EVIDENCE" : exclusionReason(context), cutoff);
                    if (eligible) {
                        BigDecimal close = candles.getLast().close();
                        ScanCandidate candidate = new ScanCandidate(
                            instrument.id(), instrument.tradingSymbol(), instrument.exchange(), interval.name(),
                            STRATEGY, context.netDirection().abs().doubleValue(), close, null, null, cutoff,
                            List.of("NEWS_ORIGINATED", "NEWS_DIRECTION_" + context.direction(),
                                "NEWS_EVIDENCE_COUNT_" + context.includedCount(),
                                "NEWS_AS_OF_" + asOf));
                        if (scans.saveCandidate(runId, candidate, "1.0.0", null,
                                instrument.catalogueVersion(), cutoff)) {
                            scans.recordNewsContext(runId, instrument.id(), STRATEGY, cutoff, news.toJson(context));
                            candidates++;
                        }
                    }
                    scanned++;
                } catch (RuntimeException instrumentFailure) {
                    diagnostics++;
                    scans.saveDiagnostic(runId, instrument.id(), STRATEGY, "ERROR", "NEWS_SCAN_FAILED",
                        "News-driven scan failed for this instrument; remaining instruments continued");
                    scanned++;
                }
            }

            if (diagnostics == 0) {
                scans.finishRun(runId, scanned, candidates);
            } else {
                String code = incompleteHistory == diagnostics
                    ? "INCOMPLETE_HISTORY_COVERAGE" : "PARTIAL_NEWS_SCAN";
                scans.partialRun(runId, scanned, candidates, code,
                    diagnostics + " instrument(s) were not fully evaluated"
                        + (incompleteHistory == 0 ? "" : "; " + incompleteHistory + " lacked the 64-candle research window"));
            }
        } catch (RuntimeException failure) {
            scans.saveDiagnostic(runId, null, STRATEGY, "ERROR", "NEWS_SCAN_FAILED",
                "News-driven scan failed before the universe could be completed");
            scans.failRun(runId, scanned, candidates, "NEWS_SCAN_FAILED",
                "News-driven scan failed before completion");
        }
        return scans.requireRun(runId);
    }

    private static boolean eligible(NewsContext context) {
        return context != null
            && NewsContext.STATUS_INCLUDED_EVIDENCE.equals(context.status())
            && context.netDirection() != null
            && context.netDirection().abs().compareTo(MIN_ABSOLUTE_DIRECTION) >= 0
            && ("POSITIVE".equals(context.direction()) || "NEGATIVE".equals(context.direction()))
            && "LOW".equals(context.uncertainty())
            && !context.blackout()
            && context.includedCount() > 0;
    }

    private static String exclusionReason(NewsContext context) {
        if (context == null) return "NEWS_CONTEXT_UNAVAILABLE";
        if (context.blackout()) return "NEWS_EVENT_BLACKOUT";
        if (context.netDirection() == null) return "NO_INCLUDED_NEWS_EVIDENCE";
        if (!"LOW".equals(context.uncertainty())) return "NEWS_UNCERTAINTY_GATE";
        if (context.netDirection().abs().compareTo(MIN_ABSOLUTE_DIRECTION) < 0) return "NEWS_DIRECTION_BELOW_THRESHOLD";
        return "NEWS_NOT_DIRECTIONAL";
    }
}
