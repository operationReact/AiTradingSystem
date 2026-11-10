package in.aitradingsystem.marketdata;

import in.aitradingsystem.provider.angelone.AppAngelOneMarketFeedService;
import java.time.Clock;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Owner-only, opt-in gradual historical bootstrap for the active
 * cash-equity catalogue. It does not establish redistribution entitlements,
 * issue orders, manufacture missing candles, or bypass provider throttling.
 *
 * One instrument per fixed-delay execution uses the existing checkpointed
 * HistoricalCandleService. A provider-wide failure backs off the whole
 * collector so per-instrument cooldowns cannot cause a full-universe storm.
 * Only one private backend collector replica is permitted.
 */
@Component
@ConditionalOnProperty(prefix = "minttick.owner-feed",
        name = {"enabled", "history-backfill-enabled"}, havingValue = "true")
public class OwnerUniverseHistoryBackfill {
    private static final Logger log = LoggerFactory.getLogger(OwnerUniverseHistoryBackfill.class);
    private static final long BASE_RETRY_SECONDS = 30;
    private static final long MAX_RETRY_SECONDS = 1800;
    private static final int MAX_FAILURE_STREAK = 7;

    private final UniverseCandleCoverageService coverage;
    private final HistoricalCandleService historical;
    private final AppAngelOneMarketFeedService appFeed;
    private final MarketDataProvider privateSource;
    private final Clock clock;
    private Instant retryAfter = Instant.EPOCH;
    private int failures;

    @Autowired
    public OwnerUniverseHistoryBackfill(UniverseCandleCoverageService coverage,
                                        HistoricalCandleService historical,
                                        AppAngelOneMarketFeedService appFeed,
                                        HistoricalCandleParser parser) {
        this(coverage, historical, appFeed, parser, Clock.systemUTC());
    }

    OwnerUniverseHistoryBackfill(UniverseCandleCoverageService coverage,
                                 HistoricalCandleService historical,
                                 AppAngelOneMarketFeedService appFeed,
                                 HistoricalCandleParser parser, Clock clock) {
        this.coverage = coverage;
        this.historical = historical;
        this.appFeed = appFeed;
        this.privateSource = new OwnerAngelOneHistoricalProvider(appFeed, parser);
        this.clock = clock;
    }

    @Scheduled(fixedDelayString = "${minttick.owner-feed.history-backfill-poll-ms:2000}")
    public synchronized void collectOne() {
        if (clock.instant().isBefore(retryAfter)) return;
        try {
            if (!appFeed.available()) {
                defer();
                return;
            }
            var next = coverage.nextDue();
            if (next.isEmpty()) return;
            var window = coverage.window();
            CandleBackfillRun run = historical.startWithProvider(next.get(),
                    HistoricalCandleInterval.FIVE_MINUTE, window.fromDate(), window.toDate(),
                    privateSource);
            // SUCCEEDED with zero rows is still incomplete coverage; that
            // instrument cools down for 18h, but need not stall all symbols.
            if (!"SUCCEEDED".equals(run.status())) {
                defer();
                log.warn("Private history backfill run {} ended in state {}",
                        run.id(), run.status());
                return;
            }
            failures = 0;
            retryAfter = Instant.EPOCH;
        } catch (RuntimeException ex) {
            defer();
            // Never print broker, AWS, provider response or customer identifiers.
            log.warn("Private historical backfill cycle unavailable: {}",
                    ex.getClass().getSimpleName());
        }
    }

    private void defer() {
        failures = Math.min(MAX_FAILURE_STREAK, failures + 1);
        long seconds = Math.min(MAX_RETRY_SECONDS,
                BASE_RETRY_SECONDS << (failures - 1));
        retryAfter = clock.instant().plusSeconds(seconds);
    }
}
