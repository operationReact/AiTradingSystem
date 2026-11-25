package in.aitradingsystem.marketdata;

import in.aitradingsystem.provider.angelone.AngelOneQuoteDetailParser;
import in.aitradingsystem.provider.angelone.AppAngelOneMarketFeedService;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataAccessException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * Whole-market NSE board for the customer Markets screen: breadth, large/mid/small/micro cap
 * segments, sector heat, top gainers/losers, most active and 52-week extremes.
 *
 * Every value comes from a stored, identity- and session-verified Angel One FULL quote. Rows from an
 * older session are excluded rather than mixed into today's movers. Cap segments come only from the
 * official Nifty 100 / Midcap 150 / Smallcap 250 / Microcap 250 lists; an un-ingested list is reported
 * as unavailable, never inferred from price or volume.
 *
 * While the cash session is open and a customer has recently looked at the board, index members
 * (at most {@link #MAX_REFRESH_TARGETS} stocks) are refreshed in 50-token FULL batches no more often
 * than the configured interval. The post-close warmer covers the rest of the catalogue.
 */
@Service
public class MarketBoardService {
    private static final Logger log = LoggerFactory.getLogger(MarketBoardService.class);
    static final ZoneId MARKET_ZONE = ZoneId.of("Asia/Kolkata");
    static final int PROVIDER_BATCH = 50;
    static final int MAX_REFRESH_TARGETS = 800;
    static final int MAX_LIMIT = 100;
    static final Duration READ_CACHE = Duration.ofSeconds(15);
    static final Duration DEMAND_WINDOW = Duration.ofMinutes(3);
    static final Duration LIVE_MAX_AGE = Duration.ofMinutes(3);
    /** "Near" a 52-week extreme: within this percentage of it. */
    static final BigDecimal NEAR_EXTREME_PCT = new BigDecimal("2.00");

    public enum Segment {
        ALL(null, "All NSE stocks"),
        NIFTY50("NIFTY50", "Nifty 50"),
        LARGE("NIFTY100", "Large cap"),
        MID("MIDCAP150", "Mid cap"),
        SMALL("SMALLCAP250", "Small cap"),
        MICRO("MICROCAP250", "Micro cap");

        final String universe;
        final String label;

        Segment(String universe, String label) {
            this.universe = universe;
            this.label = label;
        }
    }

    public enum Sort { CHANGE, GAINERS, LOSERS, ACTIVE, VOLUME, HIGH52, LOW52, GAP_UP, GAP_DOWN }

    public record Row(long instrumentId, String tradingSymbol, String companyName, String industry,
                      String capSegment, BigDecimal ltp, BigDecimal previousClose, BigDecimal change,
                      BigDecimal changePct, BigDecimal open, BigDecimal high, BigDecimal low,
                      BigDecimal gapPct, long volume, BigDecimal tradedValue, BigDecimal week52High,
                      BigDecimal week52Low, Instant exchangeAt, boolean nifty50) {}

    public record Breadth(int advancers, int decliners, int unchanged, int total) {}

    /**
     * {@code state}: LIVE while the cash session is open and quotes are recent, DELAYED while it is
     * open but the newest quote is older, LAST_SESSION when closed. Never claims a live tick stream.
     */
    public record Freshness(String state, LocalDate sessionDate, Instant asOf, int excludedOlderSession) {}

    public record MarketStatus(String state, LocalDate tradeDate, Instant opensAt, Instant closesAt) {}

    public record SegmentSummary(String segment, String label, boolean available, int members,
                                 int quoted, Breadth breadth, BigDecimal avgChangePct,
                                 BigDecimal medianChangePct, LocalDate membershipAsOf) {}

    public record SectorSummary(String sector, int quoted, int advancers, int decliners,
                                BigDecimal avgChangePct, String leader, BigDecimal leaderChangePct) {}

    public record Summary(String contractVersion, Instant generatedAt, String source,
                          MarketStatus market, Freshness freshness, long listed,
                          List<SegmentSummary> segments, List<SectorSummary> sectors) {}

    public record Board(String contractVersion, Instant generatedAt, String source, String segment,
                        String sort, String sector, boolean available, int quoted,
                        Freshness freshness, List<Row> rows) {}

    static final String SOURCE = "ANGEL_ONE_SMARTAPI_FULL_STORED";

    private final MarketBoardRepository repository;
    private final AppAngelOneMarketFeedService feed;
    private final MarketSessionCalendar calendar;
    private final MarketDetailSnapshotStore store;
    private final Clock clock;
    private final boolean intradayRefresh;
    private final Duration refreshInterval;

    private Loaded loaded;
    private volatile Instant lastDemand = Instant.EPOCH;
    private Instant lastRefresh = Instant.EPOCH;

    private record Loaded(Instant at, List<MarketBoardRepository.StoredQuote> quotes, long listed,
                          Map<String, MarketBoardRepository.UniverseState> universes) {}

    @Autowired
    public MarketBoardService(MarketBoardRepository repository, AppAngelOneMarketFeedService feed,
            MarketSessionCalendar calendar, MarketDetailSnapshotStore store, Clock clock,
            @Value("${app.market-data.board.intraday-refresh.enabled:true}") boolean intradayRefresh,
            @Value("${app.market-data.board.intraday-refresh.interval-seconds:90}") long refreshSeconds) {
        if (refreshSeconds < 30) throw new IllegalArgumentException("Board refresh interval must be >= 30s");
        this.repository = repository;
        this.feed = feed;
        this.calendar = calendar;
        this.store = store;
        this.clock = clock;
        this.intradayRefresh = intradayRefresh;
        this.refreshInterval = Duration.ofSeconds(refreshSeconds);
    }

    public Summary summary() {
        lastDemand = clock.instant();
        Loaded data = load();
        Instant now = clock.instant();
        Session session = latestSession(data.quotes(), now);
        List<Row> rows = rows(session.current());
        List<SegmentSummary> segments = new ArrayList<>();
        for (Segment segment : Segment.values()) {
            var universe = segment.universe == null ? null : data.universes().get(segment.universe);
            boolean available = segment == Segment.ALL || universe != null;
            List<Row> members = available ? filter(rows, segment, null) : List.of();
            segments.add(new SegmentSummary(segment.name(), segment.label, available,
                segment == Segment.ALL ? (int) data.listed() : universe == null ? 0 : universe.members(),
                members.size(), breadth(members), average(members), median(members),
                universe == null ? null : universe.asOf()));
        }
        return new Summary("customer.market.board-summary.v1", now, SOURCE, marketStatus(now),
            freshness(session, now), data.listed(), segments, sectors(rows));
    }

    public Board board(Segment segment, Sort sort, String sector, int limit) {
        lastDemand = clock.instant();
        int bounded = Math.max(1, Math.min(limit, MAX_LIMIT));
        Loaded data = load();
        Instant now = clock.instant();
        Session session = latestSession(data.quotes(), now);
        boolean available = segment.universe == null || data.universes().containsKey(segment.universe);
        List<Row> rows = available ? filter(rows(session.current()), segment, sector) : List.of();
        List<Row> ranked = rank(rows, sort).stream().limit(bounded).toList();
        return new Board("customer.market.board.v1", now, SOURCE, segment.name(), sort.name(), sector,
            available, rows.size(), freshness(session, now), ranked);
    }

    // ---- computation (pure, package-visible for tests) -------------------------------------

    record Session(LocalDate date, List<MarketBoardRepository.StoredQuote> current, int excluded) {}

    /** Keep only quotes whose exchange time belongs to the newest verified cash session. */
    Session latestSession(List<MarketBoardRepository.StoredQuote> quotes, Instant now) {
        Map<LocalDate, Boolean> tradingDays = new HashMap<>();
        List<MarketBoardRepository.StoredQuote> valid = new ArrayList<>();
        for (var quote : quotes) {
            if (!plausible(quote) || quote.exchangeAt() == null || quote.exchangeAt().isAfter(now)) continue;
            LocalDate day = quote.exchangeAt().atZone(MARKET_ZONE).toLocalDate();
            boolean trading = tradingDays.computeIfAbsent(day, d -> {
                try {
                    return calendar.isTradingDay(d);
                } catch (RuntimeException ex) {
                    return false;
                }
            });
            if (trading) valid.add(quote);
        }
        LocalDate latest = valid.stream().map(q -> q.exchangeAt().atZone(MARKET_ZONE).toLocalDate())
            .max(Comparator.naturalOrder()).orElse(null);
        if (latest == null) return new Session(null, List.of(), quotes.size());
        List<MarketBoardRepository.StoredQuote> current = valid.stream()
            .filter(q -> q.exchangeAt().atZone(MARKET_ZONE).toLocalDate().equals(latest)).toList();
        return new Session(latest, current, quotes.size() - current.size());
    }

    static boolean plausible(MarketBoardRepository.StoredQuote q) {
        return q.ltp() != null && q.ltp().signum() > 0 && q.previousClose() != null
            && q.previousClose().signum() > 0 && q.high() != null && q.low() != null
            && q.high().compareTo(q.low()) >= 0 && q.volume() >= 0;
    }

    static List<Row> rows(List<MarketBoardRepository.StoredQuote> quotes) {
        List<Row> rows = new ArrayList<>(quotes.size());
        for (var q : quotes) {
            BigDecimal change = q.ltp().subtract(q.previousClose());
            BigDecimal gap = q.open() == null || q.open().signum() <= 0 ? BigDecimal.ZERO
                : percent(q.open(), q.previousClose());
            BigDecimal value = q.ltp().multiply(BigDecimal.valueOf(q.volume())).setScale(0, RoundingMode.HALF_UP);
            rows.add(new Row(q.instrumentId(), q.tradingSymbol(),
                q.companyName() == null || q.companyName().isBlank()
                    ? q.tradingSymbol().replaceFirst("-EQ$", "") : q.companyName(),
                q.industry(), capSegment(q.universes()), q.ltp(), q.previousClose(),
                change.setScale(2, RoundingMode.HALF_UP), percent(q.ltp(), q.previousClose()),
                q.open(), q.high(), q.low(), gap, q.volume(), value, q.week52High(), q.week52Low(),
                q.exchangeAt(), q.universes().contains("NIFTY50")));
        }
        return rows;
    }

    static String capSegment(Set<String> universes) {
        if (universes.contains("NIFTY100") || universes.contains("NIFTY50")) return "LARGE";
        if (universes.contains("MIDCAP150")) return "MID";
        if (universes.contains("SMALLCAP250")) return "SMALL";
        if (universes.contains("MICROCAP250")) return "MICRO";
        return null;
    }

    static List<Row> filter(List<Row> rows, Segment segment, String sector) {
        return rows.stream().filter(row -> switch (segment) {
            case ALL -> true;
            case NIFTY50 -> row.nifty50();
            default -> segment.name().equals(row.capSegment());
        })
            .filter(row -> sector == null || sector.equalsIgnoreCase(row.industry()))
            .toList();
    }

    static List<Row> rank(List<Row> rows, Sort sort) {
        Comparator<Row> bySymbol = Comparator.comparing(Row::tradingSymbol);
        return switch (sort) {
            case CHANGE -> rows.stream()
                .sorted(Comparator.comparing(Row::changePct).reversed().thenComparing(bySymbol)).toList();
            case GAINERS -> rows.stream().filter(r -> r.changePct().signum() > 0)
                .sorted(Comparator.comparing(Row::changePct).reversed().thenComparing(bySymbol)).toList();
            case LOSERS -> rows.stream().filter(r -> r.changePct().signum() < 0)
                .sorted(Comparator.comparing(Row::changePct).thenComparing(bySymbol)).toList();
            case ACTIVE -> rows.stream().filter(r -> r.volume() > 0)
                .sorted(Comparator.comparing(Row::tradedValue).reversed().thenComparing(bySymbol)).toList();
            case VOLUME -> rows.stream().filter(r -> r.volume() > 0)
                .sorted(Comparator.comparingLong(Row::volume).reversed().thenComparing(bySymbol)).toList();
            case HIGH52 -> rows.stream().filter(r -> distance(r.ltp(), r.week52High()) != null
                    && distance(r.ltp(), r.week52High()).compareTo(NEAR_EXTREME_PCT) <= 0)
                .sorted(Comparator.comparing((Row r) -> distance(r.ltp(), r.week52High()))
                    .thenComparing(Comparator.comparing(Row::changePct).reversed())).toList();
            case LOW52 -> rows.stream().filter(r -> distance(r.ltp(), r.week52Low()) != null
                    && distance(r.ltp(), r.week52Low()).compareTo(NEAR_EXTREME_PCT) <= 0)
                .sorted(Comparator.comparing((Row r) -> distance(r.ltp(), r.week52Low()))
                    .thenComparing(Row::changePct)).toList();
            case GAP_UP -> rows.stream().filter(r -> r.gapPct().signum() > 0)
                .sorted(Comparator.comparing(Row::gapPct).reversed().thenComparing(bySymbol)).toList();
            case GAP_DOWN -> rows.stream().filter(r -> r.gapPct().signum() < 0)
                .sorted(Comparator.comparing(Row::gapPct).thenComparing(bySymbol)).toList();
        };
    }

    /** Absolute distance of {@code ltp} from a 52-week extreme, in percent of the extreme. */
    static BigDecimal distance(BigDecimal ltp, BigDecimal extreme) {
        if (extreme == null || extreme.signum() <= 0) return null;
        return ltp.subtract(extreme).abs().multiply(BigDecimal.valueOf(100))
            .divide(extreme, 2, RoundingMode.HALF_UP);
    }

    static Breadth breadth(List<Row> rows) {
        int up = 0;
        int down = 0;
        int flat = 0;
        for (Row row : rows) {
            int direction = row.ltp().compareTo(row.previousClose());
            if (direction > 0) up++;
            else if (direction < 0) down++;
            else flat++;
        }
        return new Breadth(up, down, flat, rows.size());
    }

    static BigDecimal average(List<Row> rows) {
        if (rows.isEmpty()) return null;
        BigDecimal sum = BigDecimal.ZERO;
        for (Row row : rows) sum = sum.add(row.changePct());
        return sum.divide(BigDecimal.valueOf(rows.size()), 2, RoundingMode.HALF_UP);
    }

    static BigDecimal median(List<Row> rows) {
        if (rows.isEmpty()) return null;
        List<BigDecimal> values = rows.stream().map(Row::changePct).sorted().toList();
        int middle = values.size() / 2;
        if (values.size() % 2 == 1) return values.get(middle);
        return values.get(middle - 1).add(values.get(middle)).divide(BigDecimal.valueOf(2), 2, RoundingMode.HALF_UP);
    }

    static List<SectorSummary> sectors(List<Row> rows) {
        Map<String, List<Row>> bySector = new TreeMap<>();
        for (Row row : rows) {
            if (row.industry() == null || row.industry().isBlank()) continue;
            bySector.computeIfAbsent(row.industry(), key -> new ArrayList<>()).add(row);
        }
        List<SectorSummary> result = new ArrayList<>();
        for (var entry : bySector.entrySet()) {
            List<Row> group = entry.getValue();
            Breadth b = breadth(group);
            Row leader = group.stream().max(Comparator.comparing(Row::changePct)
                .thenComparing(Row::tradingSymbol, Comparator.reverseOrder())).orElseThrow();
            result.add(new SectorSummary(entry.getKey(), group.size(), b.advancers(), b.decliners(),
                average(group), leader.tradingSymbol(), leader.changePct()));
        }
        result.sort(Comparator.comparing(SectorSummary::avgChangePct).reversed()
            .thenComparing(SectorSummary::sector));
        return result;
    }

    Freshness freshness(Session session, Instant now) {
        Instant asOf = session.current().stream().map(MarketBoardRepository.StoredQuote::exchangeAt)
            .max(Comparator.naturalOrder()).orElse(null);
        String state;
        if (asOf == null) state = "UNAVAILABLE";
        else if (!insideSession(now)) state = "LAST_SESSION";
        else if (Duration.between(asOf, now).compareTo(LIVE_MAX_AGE) <= 0
                && asOf.atZone(MARKET_ZONE).toLocalDate().equals(now.atZone(MARKET_ZONE).toLocalDate())) {
            state = "LIVE";
        } else state = "DELAYED";
        return new Freshness(state, session.date(), asOf, session.excluded());
    }

    MarketStatus marketStatus(Instant now) {
        LocalDate today = now.atZone(MARKET_ZONE).toLocalDate();
        try {
            MarketSession session = calendar.session(today);
            if (!session.tradingDay()) return new MarketStatus("CLOSED", today, null, null);
            Instant open = session.open().toInstant();
            Instant close = session.close().toInstant();
            String state;
            if (!now.isBefore(open) && now.isBefore(close)) state = "OPEN";
            else if (now.isBefore(open) && !now.isBefore(open.minus(Duration.ofMinutes(15)))) state = "PRE_OPEN";
            else state = "CLOSED";
            return new MarketStatus(state, today, open, close);
        } catch (RuntimeException ex) {
            return new MarketStatus("UNKNOWN", today, null, null);
        }
    }

    private boolean insideSession(Instant now) {
        try {
            return calendar.isInsideCashSession(now);
        } catch (RuntimeException ex) {
            return false;
        }
    }

    static BigDecimal percent(BigDecimal value, BigDecimal base) {
        return value.subtract(base).multiply(BigDecimal.valueOf(100)).divide(base, 2, RoundingMode.HALF_UP);
    }

    // ---- data access ---------------------------------------------------------------------

    private synchronized Loaded load() {
        Instant now = clock.instant();
        if (loaded != null && now.isBefore(loaded.at().plus(READ_CACHE))) return loaded;
        Map<String, MarketBoardRepository.UniverseState> universes = new LinkedHashMap<>();
        for (var state : repository.universes()) universes.put(state.universeCode(), state);
        loaded = new Loaded(now, repository.storedQuotes(), repository.activeNseListings(), universes);
        return loaded;
    }

    /**
     * Demand-driven intraday refresh of official index members. Runs only inside the cash session,
     * only when the application feed is configured and someone viewed the board recently.
     */
    @Scheduled(fixedDelayString = "${app.market-data.board.intraday-refresh.tick-ms:15000}",
               initialDelayString = "${app.market-data.board.intraday-refresh.initial-delay-ms:60000}")
    public void refreshTick() {
        Instant now = clock.instant();
        if (!intradayRefresh || Duration.between(lastDemand, now).compareTo(DEMAND_WINDOW) > 0
                || Duration.between(lastRefresh, now).compareTo(refreshInterval) < 0) return;
        try {
            if (!feed.available() || !calendar.isInsideCashSession(now)) return;
            lastRefresh = now;
            int saved = refreshMembers(now);
            if (saved > 0) synchronized (this) { loaded = null; }
        } catch (RuntimeException ex) {
            // No provider bodies, credentials or tokens in logs.
            log.warn("Markets board intraday refresh failed: {}", ex.getClass().getSimpleName());
        }
    }

    int refreshMembers(Instant now) {
        List<MarketBoardRepository.RefreshTarget> targets;
        try {
            targets = repository.refreshTargets(Set.of("NIFTY50", "NIFTY100", "NIFTY500", "MIDCAP150",
                "SMALLCAP250", "MICROCAP250"));
        } catch (DataAccessException ex) {
            return 0;
        }
        if (targets.size() > MAX_REFRESH_TARGETS) targets = targets.subList(0, MAX_REFRESH_TARGETS);
        int saved = 0;
        for (int from = 0; from < targets.size(); from += PROVIDER_BATCH) {
            var batch = targets.subList(from, Math.min(from + PROVIDER_BATCH, targets.size()));
            Map<String, AngelOneQuoteDetailParser.Detail> details;
            try {
                details = feed.details(batch.stream().map(t ->
                    new AngelOneQuoteDetailParser.Expected(t.symbolToken(), t.tradingSymbol())).toList());
            } catch (RuntimeException ex) {
                continue; // Preserve rows from other batches; never synthesize a failed batch.
            }
            if (details == null) continue;
            for (var target : batch) {
                var detail = details.get(target.symbolToken());
                if (detail == null || !VerifiedFullQuoteSession.accepts("NSE", detail.exchangeAt(), now, calendar)) {
                    continue;
                }
                try {
                    store.save(new CustomerMarketDataController.DetailQuote(target.instrumentId(), "NSE",
                        target.tradingSymbol(), detail.ltp(), detail.open(), detail.high(), detail.low(),
                        detail.previousClose(), detail.volume(), detail.exchangeAt(), now,
                        "ANGEL_ONE_SMARTAPI_FULL_APP", "ON_DEMAND", detail.averagePrice(),
                        detail.week52High(), detail.week52Low(), detail.totalBuyQuantity(),
                        detail.totalSellQuantity(), detail.bids(), detail.asks()));
                    saved++;
                } catch (DataAccessException | IllegalStateException ex) {
                    // A failed write must not abort the remaining verified rows.
                }
            }
        }
        return saved;
    }

    static Segment parseSegment(String value) {
        try {
            return Segment.valueOf(value == null ? "ALL" : value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            throw new MarketDataException("INVALID_BOARD_SEGMENT", "Unknown market segment");
        }
    }

    static Sort parseSort(String value) {
        try {
            return Sort.valueOf(value == null ? "GAINERS" : value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            throw new MarketDataException("INVALID_BOARD_SORT", "Unknown market board sort");
        }
    }
}
