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
import java.util.TreeMap;
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
    static final Duration CACHE_TTL = Duration.ofSeconds(30);
    static final ZoneId MARKET_ZONE = ZoneId.of("Asia/Kolkata");
    static final String INDEX_LIVE = "LIVE";
    static final String INDEX_STALE = "STALE";
    static final String INDEX_LAST_CLOSE = "LAST_CLOSE";

    private final MarketOverviewRepository repository;
    private final AppAngelOneMarketFeedService feed;
    private final MarketSessionCalendar calendar;
    private final Clock clock;
    private Overview cached;

    public MarketOverviewService(MarketOverviewRepository repository, AppAngelOneMarketFeedService feed,
            MarketSessionCalendar calendar, Clock clock) {
        this.repository = repository;
        this.feed = feed;
        this.calendar = calendar;
        this.clock = clock;
    }

    /** Whether the cash market is open now, and if so when today's session opened. */
    record IndexSession(boolean insideCashSession, Instant sessionOpen) {
        static final IndexSession CLOSED = new IndexSession(false, null);
    }

    public record Mover(String tradingSymbol, String companyName, String sector, BigDecimal ltp,
                        BigDecimal previousClose, BigDecimal changePct, BigDecimal gapPct, Instant exchangeAt) {}

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
                           List<IndexRow> indices) {}

    /** Cached for {@link #CACHE_TTL} so repeated page loads share one provider request per batch. */
    public synchronized Overview overview() {
        Instant now = clock.instant();
        if (cached != null && now.isBefore(cached.generatedAt().plus(CACHE_TTL))) return cached;

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
        cached = compute(now, members, tokens, stockDetails, indexDefinitions, indexDetails, indexSession(now));
        return cached;
    }

    private IndexSession indexSession(Instant now) {
        if (!calendar.isInsideCashSession(now)) return IndexSession.CLOSED;
        LocalDate today = now.atZone(MARKET_ZONE).toLocalDate();
        return new IndexSession(true, calendar.session(today).open().toInstant());
    }

    static String indexFreshness(Instant exchangeAt, IndexSession session) {
        if (!session.insideCashSession() || session.sessionOpen() == null) return INDEX_LAST_CLOSE;
        return exchangeAt.isBefore(session.sessionOpen()) ? INDEX_STALE : INDEX_LIVE;
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
                percent(detail.open(), detail.previousClose()), detail.exchangeAt()));
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
                indexFreshness(detail.exchangeAt(), indexSession)));
        }
        indices.addAll(indexByCode.values());

        return new Overview(generatedAt, SOURCE, BREADTH_SCOPE, exchangeAsOf,
            new Universe(UNIVERSE_CODE, sourceAsOf, members.size(), resolved, rows.size()),
            new Breadth(advancers, decliners, unchanged, rows.size()),
            gainers, losers, gapUp, gapDown, sectors, indices);
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
