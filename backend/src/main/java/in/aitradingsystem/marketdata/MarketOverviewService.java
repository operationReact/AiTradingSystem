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
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;

/**
 * Deterministic Markets overview computed from one application-session FULL bulk quote per batch of
 * at most 50 NSE tokens. Breadth, movers and sector averages cover only the Nifty 50 universe and say so;
 * no value is estimated, and a missing symbol is counted as unavailable rather than filled in.
 */
@Service
public class MarketOverviewService {
    static final String UNIVERSE_CODE = "NIFTY50";
    static final String SOURCE = "ANGEL_ONE_SMARTAPI_FULL_BULK";
    static final String BREADTH_SCOPE = "NIFTY50_CONSTITUENTS";
    static final int PROVIDER_BATCH = 50;
    static final int LIST_LIMIT = 5;
    static final BigDecimal MOMENTUM_PCT = new BigDecimal("2.00");
    static final BigDecimal GAP_UP_PCT = new BigDecimal("1.00");
    static final Duration CACHE_TTL = Duration.ofSeconds(30);
    // Exchange timestamps older than this are not current screening evidence.
    static final Duration LIVE_QUOTE_MAX_AGE = Duration.ofMinutes(2);
    static final ZoneId MARKET_ZONE = ZoneId.of("Asia/Kolkata");
    static final String INDEX_LIVE = "LIVE";
    static final String INDEX_STALE = "STALE";
    static final String INDEX_LAST_CLOSE = "LAST_CLOSE";

    static final String STORED_SOURCE = "STORED_LAST_OVERVIEW";

    private final MarketOverviewRepository repository;
    private final AppAngelOneMarketFeedService feed;
    private final MarketSessionCalendar calendar;
    private final Clock clock;
    private final MarketOverviewSnapshotStore store;
    private Overview cached;

    public MarketOverviewService(MarketOverviewRepository repository, AppAngelOneMarketFeedService feed,
            MarketSessionCalendar calendar, Clock clock, MarketOverviewSnapshotStore store) {
        this.repository = repository;
        this.feed = feed;
        this.calendar = calendar;
        this.clock = clock;
        this.store = store;
    }

    /** Whether the cash market is open now, and if so when today's session opened. */
    record IndexSession(boolean insideCashSession, Instant sessionOpen) {
        static final IndexSession CLOSED = new IndexSession(false, null);
    }

    public record Mover(String tradingSymbol, String companyName, String sector, BigDecimal ltp,
                        BigDecimal previousClose, BigDecimal changePct, BigDecimal gapPct, Instant exchangeAt,
                        BigDecimal week52High) {}

    /**
     * A deterministic rule-screen hit from verified quotes. {@code signal} is BREAKOUT (last traded at or
     * above the 52-week high), MOMENTUM (change at least {@link #MOMENTUM_PCT}) or GAP_UP (open gap at least
     * {@link #GAP_UP_PCT}). These are screen results, not recommendations.
     */
    public record Idea(String tradingSymbol, String companyName, BigDecimal ltp, BigDecimal changePct,
                       BigDecimal gapPct, String signal, Instant exchangeAt) {}

    public record Breadth(int advancers, int decliners, int unchanged, int total) {}

    public record SectorSummary(String sector, int members, int advancers, int decliners,
                                BigDecimal avgChangePct) {}

    /**
     * {@code freshness} is LIVE when the exchange timestamp is in today's cash session, STALE when the
     * market is open but the last exchange timestamp predates it, and LAST_CLOSE outside the session.
     */
    public record IndexRow(String indexCode, String displayName, BigDecimal ltp, BigDecimal previousClose,
                           BigDecimal changePct, BigDecimal open, BigDecimal high, BigDecimal low,
                           long volume, Instant exchangeAt, String freshness) {}

    public record Universe(String code, LocalDate sourceAsOf, int members, int resolved, int returned) {}

    public record Overview(Instant generatedAt, String source, String breadthScope, Instant exchangeAsOf,
                           Universe universe, Breadth breadth, List<Mover> gainers, List<Mover> losers,
                           List<Mover> gapUp, List<Mover> gapDown, List<SectorSummary> sectors,
                           List<IndexRow> indices, List<Idea> ideas) {}

    /** Cached for {@link #CACHE_TTL} so repeated page loads share one provider request per batch. */
    public synchronized Overview overview() {
        Instant now = clock.instant();
        if (cached != null && now.isBefore(cached.generatedAt().plus(CACHE_TTL))) return cached;
        try {
            cached = computeLive(now);
        } catch (RuntimeException ex) {
            Optional<Overview> stored = storedOverview();
            if (stored.isPresent()) return stored.get();
            throw ex;
        }
        saveQuietly(cached);
        return cached;
    }

    /** The last overview persisted from a verified live computation, labelled as stored; empty if none exists. */
    public Optional<Overview> storedOverview() {
        Optional<Overview> stored;
        try {
            stored = store.load();
        } catch (DataAccessException | IllegalStateException ex) {
            // A missing migration or unreadable stored payload is not valid market evidence.
            return Optional.empty();
        }
        return stored.map(MarketOverviewService::asStored);
    }

    static Overview asStored(Overview overview) {
        List<IndexRow> indices = overview.indices().stream().map(i -> new IndexRow(i.indexCode(), i.displayName(),
                i.ltp(), i.previousClose(), i.changePct(), i.open(), i.high(), i.low(), i.volume(),
                i.exchangeAt(), INDEX_LAST_CLOSE)).toList();
        return new Overview(overview.generatedAt(), STORED_SOURCE, overview.breadthScope(),
                overview.exchangeAsOf(), overview.universe(), overview.breadth(), overview.gainers(),
                overview.losers(), overview.gapUp(), overview.gapDown(), overview.sectors(), indices,
                List.of()); // Never replay old screening signals as current opportunities.
    }

    private void saveQuietly(Overview overview) {
        try {
            store.save(overview);
        } catch (DataAccessException | IllegalStateException ex) {
            // A failed snapshot write must not hide a verified live overview.
        }
    }

    private Overview computeLive(Instant now) {
        List<MarketOverviewRepository.Member> members = repository.universe(UNIVERSE_CODE);
        List<MarketOverviewRepository.IndexDefinition> indexDefinitions = repository.indices();
        Map<String, String> tokens = repository.resolveEquityTokens(
            members.stream().map(MarketOverviewRepository.Member::tradingSymbol).toList());

        List<AngelOneQuoteDetailParser.Expected> stockExpected = new ArrayList<>();
        for (MarketOverviewRepository.Member member : members) {
            String token = tokens.get(member.tradingSymbol());
            if (token != null) stockExpected.add(new AngelOneQuoteDetailParser.Expected(token, member.tradingSymbol()));
        }
        List<AngelOneQuoteDetailParser.Expected> indexExpected = indexDefinitions.stream()
            .map(d -> new AngelOneQuoteDetailParser.Expected(d.symbolToken(), d.displayName())).toList();

        Map<String, AngelOneQuoteDetailParser.Detail> stockDetails = fetchInBatches(stockExpected);
        Map<String, AngelOneQuoteDetailParser.Detail> indexDetails = fetchInBatches(indexExpected);
        return compute(now, members, tokens, stockDetails, indexDefinitions, indexDetails, indexSession(now));
    }

    private IndexSession indexSession(Instant now) {
        if (!calendar.isInsideCashSession(now)) return IndexSession.CLOSED;
        LocalDate today = now.atZone(MARKET_ZONE).toLocalDate();
        return new IndexSession(true, calendar.session(today).open().toInstant());
    }

    static String indexFreshness(Instant exchangeAt, IndexSession session, Instant evaluatedAt) {
        if (!session.insideCashSession() || session.sessionOpen() == null) return INDEX_LAST_CLOSE;
        if (exchangeAt == null || exchangeAt.isBefore(session.sessionOpen())
                || exchangeAt.isAfter(evaluatedAt)
                || Duration.between(exchangeAt, evaluatedAt).compareTo(LIVE_QUOTE_MAX_AGE) > 0) {
            return INDEX_STALE;
        }
        return INDEX_LIVE;
    }

    private Map<String, AngelOneQuoteDetailParser.Detail> fetchInBatches(
            List<AngelOneQuoteDetailParser.Expected> expected) {
        Map<String, AngelOneQuoteDetailParser.Detail> result = new HashMap<>();
        for (int from = 0; from < expected.size(); from += PROVIDER_BATCH) {
            List<AngelOneQuoteDetailParser.Expected> chunk =
                expected.subList(from, Math.min(from + PROVIDER_BATCH, expected.size()));
            result.putAll(feed.details(chunk));
        }
        return result;
    }

    static Overview compute(Instant generatedAt, List<MarketOverviewRepository.Member> members,
                            Map<String, String> tokenByTradingSymbol,
                            Map<String, AngelOneQuoteDetailParser.Detail> stockDetailsByToken,
                            List<MarketOverviewRepository.IndexDefinition> indexDefinitions,
                            Map<String, AngelOneQuoteDetailParser.Detail> indexDetailsByToken) {
        return compute(generatedAt, members, tokenByTradingSymbol, stockDetailsByToken, indexDefinitions,
            indexDetailsByToken, IndexSession.CLOSED);
    }

    /** Pure computation over already-verified provider rows; exposed for deterministic tests. */
    static Overview compute(Instant generatedAt, List<MarketOverviewRepository.Member> members,
                            Map<String, String> tokenByTradingSymbol,
                            Map<String, AngelOneQuoteDetailParser.Detail> stockDetailsByToken,
                            List<MarketOverviewRepository.IndexDefinition> indexDefinitions,
                            Map<String, AngelOneQuoteDetailParser.Detail> indexDetailsByToken,
                            IndexSession indexSession) {
        List<Mover> rows = new ArrayList<>();
        LocalDate sourceAsOf = null;
        int resolved = 0;
        for (MarketOverviewRepository.Member member : members) {
            if (sourceAsOf == null || member.asOf().isAfter(sourceAsOf)) sourceAsOf = member.asOf();
            String token = tokenByTradingSymbol.get(member.tradingSymbol());
            if (token == null) continue;
            resolved++;
            AngelOneQuoteDetailParser.Detail detail = stockDetailsByToken.get(token);
            if (detail == null) continue;
            rows.add(new Mover(member.tradingSymbol(), member.companyName(), member.sector(), detail.ltp(),
                detail.previousClose(), percent(detail.ltp(), detail.previousClose()),
                percent(detail.open(), detail.previousClose()), detail.exchangeAt(), detail.week52High()));
        }

        int advancers = 0;
        int decliners = 0;
        int unchanged = 0;
        Instant exchangeAsOf = null;
        for (Mover row : rows) {
            int direction = row.ltp().compareTo(row.previousClose());
            if (direction > 0) advancers++;
            else if (direction < 0) decliners++;
            else unchanged++;
            exchangeAsOf = latest(exchangeAsOf, row.exchangeAt());
        }

        List<Mover> gainers = rows.stream().filter(r -> r.ltp().compareTo(r.previousClose()) > 0)
            .sorted(Comparator.comparing(Mover::changePct).reversed().thenComparing(Mover::tradingSymbol))
            .limit(LIST_LIMIT).toList();
        List<Mover> losers = rows.stream().filter(r -> r.ltp().compareTo(r.previousClose()) < 0)
            .sorted(Comparator.comparing(Mover::changePct).thenComparing(Mover::tradingSymbol))
            .limit(LIST_LIMIT).toList();
        List<Mover> gapUp = rows.stream().filter(r -> r.gapPct().signum() > 0)
            .sorted(Comparator.comparing(Mover::gapPct).reversed().thenComparing(Mover::tradingSymbol))
            .limit(LIST_LIMIT).toList();
        List<Mover> gapDown = rows.stream().filter(r -> r.gapPct().signum() < 0)
            .sorted(Comparator.comparing(Mover::gapPct).thenComparing(Mover::tradingSymbol))
            .limit(LIST_LIMIT).toList();

        Map<String, List<Mover>> bySector = new TreeMap<>();
        for (Mover row : rows) bySector.computeIfAbsent(row.sector(), k -> new ArrayList<>()).add(row);
        List<SectorSummary> sectors = new ArrayList<>();
        for (Map.Entry<String, List<Mover>> entry : bySector.entrySet()) {
            List<Mover> group = entry.getValue();
            BigDecimal sum = BigDecimal.ZERO;
            int up = 0;
            int down = 0;
            for (Mover row : group) {
                sum = sum.add(row.changePct());
                int direction = row.ltp().compareTo(row.previousClose());
                if (direction > 0) up++;
                else if (direction < 0) down++;
            }
            BigDecimal average = sum.divide(BigDecimal.valueOf(group.size()), 2, RoundingMode.HALF_UP);
            sectors.add(new SectorSummary(entry.getKey(), group.size(), up, down, average));
        }
        sectors.sort(Comparator.comparing(SectorSummary::avgChangePct).reversed()
            .thenComparing(SectorSummary::sector));

        List<IndexRow> indices = new ArrayList<>();
        Map<String, IndexRow> indexByCode = new LinkedHashMap<>();
        for (MarketOverviewRepository.IndexDefinition definition : indexDefinitions) {
            AngelOneQuoteDetailParser.Detail detail = indexDetailsByToken.get(definition.symbolToken());
            if (detail == null) continue;
            exchangeAsOf = latest(exchangeAsOf, detail.exchangeAt());
            indexByCode.put(definition.indexCode(), new IndexRow(definition.indexCode(), definition.displayName(),
                detail.ltp(), detail.previousClose(), percent(detail.ltp(), detail.previousClose()),
                detail.open(), detail.high(), detail.low(), detail.volume(), detail.exchangeAt(),
                indexFreshness(detail.exchangeAt(), indexSession, generatedAt)));
        }
        indices.addAll(indexByCode.values());

        return new Overview(generatedAt, SOURCE, BREADTH_SCOPE, exchangeAsOf,
            new Universe(UNIVERSE_CODE, sourceAsOf, members.size(), resolved, rows.size()),
            new Breadth(advancers, decliners, unchanged, rows.size()),
            gainers, losers, gapUp, gapDown, sectors, indices,
            ideas(rows, generatedAt, indexSession));
    }

    static List<Idea> ideas(List<Mover> rows, Instant generatedAt, IndexSession session) {
        // Market ideas are ephemeral screening observations, not historical investment advice.
        // Do not publish them outside an exchange session or from expired/future ticks.
        if (!session.insideCashSession() || session.sessionOpen() == null) return List.of();
        List<Idea> hits = new ArrayList<>();
        for (Mover row : rows) {
            Instant tick = row.exchangeAt();
            if (tick == null || tick.isBefore(session.sessionOpen()) || tick.isAfter(generatedAt)
                    || Duration.between(tick, generatedAt).compareTo(LIVE_QUOTE_MAX_AGE) > 0) {
                continue;
            }
            String signal = null;
            if (row.week52High() != null && row.ltp().compareTo(row.week52High()) >= 0) signal = "BREAKOUT";
            else if (row.changePct().compareTo(MOMENTUM_PCT) >= 0) signal = "MOMENTUM";
            else if (row.gapPct().compareTo(GAP_UP_PCT) >= 0) signal = "GAP_UP";
            if (signal == null) continue;
            hits.add(new Idea(row.tradingSymbol(), row.companyName(), row.ltp(), row.changePct(),
                row.gapPct(), signal, row.exchangeAt()));
        }
        hits.sort(Comparator.comparing(Idea::changePct).reversed().thenComparing(Idea::tradingSymbol));
        return hits.size() > LIST_LIMIT ? new ArrayList<>(hits.subList(0, LIST_LIMIT)) : hits;
    }

    private static BigDecimal percent(BigDecimal value, BigDecimal base) {
        return value.subtract(base).multiply(BigDecimal.valueOf(100))
            .divide(base, 2, RoundingMode.HALF_UP);
    }

    private static Instant latest(Instant current, Instant candidate) {
        if (candidate == null) return current;
        return current == null || candidate.isAfter(current) ? candidate : current;
    }
}
