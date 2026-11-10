package in.aitradingsystem.marketdata;

import in.aitradingsystem.provider.angelone.AppAngelOneMarketFeedService;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Owner-only, opt-in gradual historical bootstrap for the entire active
 * cash-equity catalogue. It does not establish redistribution entitlements,
 * issue orders, manufacture missing candles, or bypass provider throttling.
 *
 * One instrument per fixed-delay execution uses the existing checkpointed
 * HistoricalCandleService and its SmartAPI historical rate limiter.
 * The private deployment must run a single collector replica.
 */
@Component
@ConditionalOnProperty(prefix = "minttick.owner-feed",
        name = {"enabled", "history-backfill-enabled"}, havingValue = "true")
public class OwnerUniverseHistoryBackfill {
    private static final Logger log = LoggerFactory.getLogger(OwnerUniverseHistoryBackfill.class);

    private final UniverseCandleCoverageService coverage;
    private final HistoricalCandleService historical;
    private final MarketDataProvider privateSource;

    public OwnerUniverseHistoryBackfill(UniverseCandleCoverageService coverage,
                                        HistoricalCandleService historical,
                                        AppAngelOneMarketFeedService appFeed,
                                        HistoricalCandleParser parser) {
        this.coverage = coverage;
        this.historical = historical;
        this.privateSource = new OwnerAngelOneHistoricalProvider(appFeed, parser);
    }

    @Scheduled(fixedDelayString = "${minttick.owner-feed.history-backfill-poll-ms:2000}")
    public void collectOne() {
        try {
            var next = coverage.nextDue();
            if (next.isEmpty()) return;
            var window = coverage.window();
            CandleBackfillRun run = historical.startWithProvider(next.get(),
                    HistoricalCandleInterval.FIVE_MINUTE, window.fromDate(), window.toDate(),
                    privateSource);
            // A run marked SUCCEEDED with zero rows does not imply candle coverage;
            // the same instrument remains under-covered and cools down before retry.
            if (!"SUCCEEDED".equals(run.status())) {
                log.warn("Private history backfill run {} ended in state {}",
                        run.id(), run.status());
            }
        } catch (RuntimeException ex) {
            // No broker, AWS credential, provider response or customer identifiers in logs.
            log.warn("Private historical backfill cycle unavailable: {}",
                    ex.getClass().getSimpleName());
        }
    }
}
