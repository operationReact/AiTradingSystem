package in.aitradingsystem.provider.angelone;

import in.aitradingsystem.instrument.Instrument;
import in.aitradingsystem.marketdata.MarketLastTickStore;
import in.aitradingsystem.marketdata.MarketSessionCalendar;
import in.aitradingsystem.scanner.ResearchScanRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Opt-in cash-session cache warmer for the same application-owned Angel One
 * session used by customer market-data APIs. Never signs in a separate owner
 * UUID, opens a customer trading session, or places an order.
 *
 * The WebSocket V2 implementation supports at most 50 watched instruments per
 * shared feed. Subscribing an arbitrary 1,000-stock universe on every poll
 * would exhaust the watch budget and omit homepage instruments.
 */
@Component
@ConditionalOnProperty(name = "minttick.owner-feed.enabled", havingValue = "true")
public class OwnerLiveFeedWarmer {
    private static final Logger log = LoggerFactory.getLogger(OwnerLiveFeedWarmer.class);
    private static final int MAX_WATCHED = CustomerAngelOneLiveQuoteService.MAX_WATCHED_PER_FEED;

    private final AppAngelOneMarketFeedService appFeed;
    private final MarketSessionCalendar calendar;
    private final ResearchScanRepository scans;
    private final MarketLastTickStore lastTicks;
    private final Clock clock;
    private final int universeLimit;

    @Autowired
    public OwnerLiveFeedWarmer(AppAngelOneMarketFeedService appFeed,
            MarketSessionCalendar calendar,
            ResearchScanRepository scans,
            MarketLastTickStore lastTicks,
            @Value("${minttick.owner-feed.universe-limit:50}") int universeLimit) {
        this(appFeed, calendar, scans, lastTicks, Clock.systemUTC(), universeLimit);
    }

    OwnerLiveFeedWarmer(AppAngelOneMarketFeedService appFeed,
            MarketSessionCalendar calendar,
            ResearchScanRepository scans,
            MarketLastTickStore lastTicks,
            Clock clock,
            int universeLimit) {
        if (universeLimit < 1) throw new IllegalArgumentException("universeLimit must be positive");
        this.appFeed = appFeed;
        this.calendar = calendar;
        this.scans = scans;
        this.lastTicks = lastTicks;
        this.clock = clock;
        this.universeLimit = Math.min(universeLimit, MAX_WATCHED);
    }

    @Scheduled(fixedDelayString = "${minttick.owner-feed.poll-ms:10000}")
    public void warm() {
        try {
            Instant now = clock.instant();
            if (!calendar.isInsideCashSession(now) || !appFeed.available()) return;
            List<Instrument> universe = scans.marketFeedPriorityUniverse(universeLimit);
            if (universe.isEmpty()) return;
            // The app-owned session is shared with quote/detail/overview APIs.
            // recordLive ignores CONNECTING, STALE, UNAVAILABLE and MARKET_CLOSED.
            lastTicks.recordLive(appFeed.snapshot(universe));
        } catch (RuntimeException ex) {
            // Never log SSM contents, provider response text, JWT or feed token.
            log.warn("Application market feed warm-up failed: {}", ex.getClass().getSimpleName());
        }
    }
}
