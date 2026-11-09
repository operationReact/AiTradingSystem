package in.aitradingsystem.provider.angelone;

import in.aitradingsystem.instrument.Instrument;
import in.aitradingsystem.marketdata.MarketLastTickStore;
import in.aitradingsystem.marketdata.MarketSessionCalendar;
import in.aitradingsystem.scanner.ResearchScanRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Keeps the shared owner Angel One feed subscribed during cash hours so backend consumers
 * and customer reads receive live ticks without a phone session driving the stream.
 */
@Component
@ConditionalOnProperty(name = "minttick.owner-feed.enabled", havingValue = "true")
public class OwnerLiveFeedWarmer {

    private static final Logger log = LoggerFactory.getLogger(OwnerLiveFeedWarmer.class);

    private final CustomerAngelOneLiveQuoteService liveQuotes;
    private final CustomerAngelOneSessionService sessions;
    private final OwnerAngelOneSsmService ownerSsm;
    private final MarketSessionCalendar calendar;
    private final ResearchScanRepository scans;
    private final MarketLastTickStore lastTicks;
    private final Clock clock;
    private final UUID owner;
    private final int universeLimit;

    @Autowired
    public OwnerLiveFeedWarmer(CustomerAngelOneLiveQuoteService liveQuotes,
            CustomerAngelOneSessionService sessions,
            OwnerAngelOneSsmService ownerSsm,
            MarketSessionCalendar calendar,
            ResearchScanRepository scans,
            MarketLastTickStore lastTicks,
            @Value("${minttick.owner-feed.user-id}") String ownerUserId,
            @Value("${minttick.owner-feed.universe-limit:1000}") int universeLimit) {
        this(liveQuotes, sessions, ownerSsm, calendar, scans, lastTicks, Clock.systemUTC(),
                UUID.fromString(ownerUserId.trim()), universeLimit);
    }

    OwnerLiveFeedWarmer(CustomerAngelOneLiveQuoteService liveQuotes,
            CustomerAngelOneSessionService sessions,
            OwnerAngelOneSsmService ownerSsm,
            MarketSessionCalendar calendar,
            ResearchScanRepository scans,
            MarketLastTickStore lastTicks,
            Clock clock,
            UUID owner,
            int universeLimit) {
        if (universeLimit < 1) throw new IllegalArgumentException("universeLimit must be positive");
        this.liveQuotes = liveQuotes;
        this.sessions = sessions;
        this.ownerSsm = ownerSsm;
        this.calendar = calendar;
        this.scans = scans;
        this.lastTicks = lastTicks;
        this.clock = clock;
        this.owner = owner;
        this.universeLimit = universeLimit;
    }

    @Scheduled(fixedDelayString = "${minttick.owner-feed.poll-ms:2000}")
    public void warm() {
        try {
            Instant now = clock.instant();
            if (!calendar.isInsideCashSession(now)) return;
            var credentials = sessions.credentialsForStream(owner);
            if ((credentials == null || !credentials.expiresAt().isAfter(now)) && ownerSsm.available()) {
                ownerSsm.connect(owner);
            }
            List<Instrument> universe = scans.activeCashEquityUniverse(universeLimit);
            if (universe.isEmpty()) return;
            lastTicks.recordLive(liveQuotes.snapshot(owner, universe));
        } catch (RuntimeException ex) {
            log.warn("Owner live feed warm-up failed: {}", ex.getClass().getSimpleName());
        }
    }
}
