package in.aitradingsystem.marketdata;

import in.aitradingsystem.instrument.Instrument;
import in.aitradingsystem.provider.angelone.AngelOneQuoteDetailParser;
import in.aitradingsystem.provider.angelone.AppAngelOneMarketFeedService;
import in.aitradingsystem.scanner.ResearchScanRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Owner-private, read-only NSE/BSE cash post-close FULL quote/High/Low refresher.
 * The 15:40-16:00 IST window starts after NSE's Closing Auction Session.
 * BSE rows must carry an exchange timestamp from the ordinary cash session.
 * Bounded 50-token batches at a conservative 5-second interval; no customer broker connection.
 * These are verified broker observations, never official exchange close claims.
 */
@Component
@ConditionalOnProperty(name = "minttick.owner-feed.enabled", havingValue = "true")
public final class OwnerPostCloseFullQuoteWarmer {
    private static final Logger log = LoggerFactory.getLogger(OwnerPostCloseFullQuoteWarmer.class);
    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    private static final LocalTime START = LocalTime.of(15, 40);
    private static final LocalTime END = LocalTime.of(16, 0);
    private static final int BATCH = 50;
    private static final int MAX_ATTEMPTS = 3;

    private final AppAngelOneMarketFeedService feed;
    private final MarketSessionCalendar calendar;
    private final ResearchScanRepository scans;
    private final MarketDetailSnapshotStore store;
    private final Clock clock;
    private final int universeLimit;

    private LocalDate sessionDate;
    private List<Instrument> queue = new ArrayList<>();
    private final Map<Long, Integer> attempts = new HashMap<>();
    private int cursor;

    @Autowired
    public OwnerPostCloseFullQuoteWarmer(AppAngelOneMarketFeedService feed,
            MarketSessionCalendar calendar, ResearchScanRepository scans,
            MarketDetailSnapshotStore store,
            @Value("${minttick.owner-feed.postclose-universe-limit:6000}") int universeLimit) {
        this(feed, calendar, scans, store, Clock.systemUTC(), universeLimit);
    }

    OwnerPostCloseFullQuoteWarmer(AppAngelOneMarketFeedService feed,
            MarketSessionCalendar calendar, ResearchScanRepository scans,
            MarketDetailSnapshotStore store, Clock clock, int universeLimit) {
        if (universeLimit < 1) throw new IllegalArgumentException("universeLimit must be positive");
        this.feed = feed;
        this.calendar = calendar;
        this.scans = scans;
        this.store = store;
        this.clock = clock;
        this.universeLimit = Math.min(universeLimit, 10000);
    }

    static boolean eligibleCashEquity(Instrument i) {
        if (i == null) return false;
        if ("NSE".equals(i.exchange())) {
            return "NSE_CM".equals(i.exchangeSegment()) && i.tradingSymbol().endsWith("-EQ");
        }
        if ("BSE".equals(i.exchange())) {
            return "BSE_CM".equals(i.exchangeSegment())
                && ("EQ".equalsIgnoreCase(i.instrumentType()) || i.tradingSymbol().endsWith("-EQ"));
        }
        return false;
    }

    /** Alternating 50-token blocks keep NSE and BSE independently verifiable and paced. */
    static List<Instrument> exchangeBatches(List<Instrument> eligible) {
        List<Instrument> nse = eligible.stream().filter(i -> "NSE".equals(i.exchange())).toList();
        List<Instrument> bse = eligible.stream().filter(i -> "BSE".equals(i.exchange())).toList();
        List<Instrument> ordered = new ArrayList<>(eligible.size());
        int length = Math.max(nse.size(), bse.size());
        for (int from = 0; from < length; from += BATCH) {
            if (from < nse.size()) ordered.addAll(nse.subList(from, Math.min(from + BATCH, nse.size())));
            if (from < bse.size()) ordered.addAll(bse.subList(from, Math.min(from + BATCH, bse.size())));
        }
        return ordered;
    }

    @Scheduled(fixedDelayString = "${minttick.owner-feed.postclose-poll-ms:5000}")
    public synchronized void reconcile() {
        Instant now = clock.instant();
        var local = now.atZone(IST);
        var time = local.toLocalTime();
        if (time.isBefore(START) || !time.isBefore(END)) return;
        try {
            var session = calendar.session(local.toLocalDate());
            if (!session.tradingDay() || !session.close().toLocalTime().equals(LocalTime.of(15, 30))
                    || !feed.available()) return;
            if (!local.toLocalDate().equals(sessionDate)) {
                // The REST FULL transport supports both cash exchanges. Never
                // assign BSE's untyped symbol codes to EQ without explicit evidence.
                // Alternate exchange batches so neither market is completely starved.
                queue = exchangeBatches(scans.postCloseFullQuoteUniverse(universeLimit).stream()
                    .filter(OwnerPostCloseFullQuoteWarmer::eligibleCashEquity).toList());
                sessionDate = local.toLocalDate();
                cursor = 0;
                attempts.clear();
            }
            if (cursor >= queue.size()) return;
            String exchange = queue.get(cursor).exchange();
            int end = cursor;
            while (end < queue.size() && end - cursor < BATCH
                    && exchange.equals(queue.get(end).exchange())) end++;
            List<Instrument> batch = List.copyOf(queue.subList(cursor, end));
            var expected = batch.stream().map(i ->
                new AngelOneQuoteDetailParser.Expected(i.symbolToken(), i.tradingSymbol())).toList();
            Map<String, AngelOneQuoteDetailParser.Detail> fetched;
            try {
                fetched = "NSE".equals(exchange)
                    ? feed.details(expected) : feed.details(exchange, expected);
            } catch (RuntimeException ex) {
                fetched = Map.of();
                // No provider response bodies, credentials or tokens in logs.
                log.warn("Post-close broker FULL quote batch unavailable: {}", ex.getClass().getSimpleName());
            }
            if (fetched == null) fetched = Map.of();
            for (Instrument instrument : batch) {
                var detail = fetched.get(instrument.symbolToken());
                if (detail != null && VerifiedFullQuoteSession.accepts(
                        instrument.exchange(), detail.exchangeAt(), now, calendar)
                        && detail.exchangeAt().atZone(IST).toLocalDate().equals(sessionDate)) {
                    store.save(new CustomerMarketDataController.DetailQuote(
                        instrument.id(), instrument.exchange(), instrument.tradingSymbol(),
                        detail.ltp(), detail.open(), detail.high(), detail.low(),
                        detail.previousClose(), detail.volume(), detail.exchangeAt(), now,
                        "ANGEL_ONE_SMARTAPI_FULL_APP", "ON_DEMAND",
                        detail.averagePrice(), detail.week52High(), detail.week52Low(),
                        detail.totalBuyQuantity(), detail.totalSellQuantity(),
                        detail.bids(), detail.asks()));
                } else if (attempts.merge(instrument.id(), 1, Integer::sum) < MAX_ATTEMPTS) {
                    queue.add(instrument);
                }
            }
            cursor = end;
        } catch (RuntimeException ex) {
            log.warn("Post-close quote reconciliation failed: {}", ex.getClass().getSimpleName());
        }
    }
}
