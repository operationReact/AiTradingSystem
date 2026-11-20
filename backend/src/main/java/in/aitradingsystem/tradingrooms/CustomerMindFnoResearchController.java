package in.aitradingsystem.tradingrooms;

import in.aitradingsystem.instrument.Instrument;
import in.aitradingsystem.instrument.InstrumentMasterService;
import in.aitradingsystem.marketdata.MarketCandle;
import in.aitradingsystem.marketdata.MarketDataRepository;
import in.aitradingsystem.provider.upstox.UpstoxAnalyticsSource;
import java.math.BigDecimal;
import java.math.MathContext;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * Read-only Mind F&O research: the same authenticated, permission-gated Upstox
 * discovery used by Markets plus completed, source-attributed NIFTY daily bars.
 * Never sends PAPER or LIVE orders; no guessed expiries, contracts or quotes.
 */
@RestController
@RequestMapping("/api/customer/mind/v1")
public final class CustomerMindFnoResearchController {
    private static final String UNDERLYING = FnoLongOptionCrossoverV1.UNDERLYING;
    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    private static final LocalTime SESSION_CLOSE = LocalTime.of(15, 30);
    private static final Duration MAX_QUOTE_AGE = Duration.ofSeconds(15);
    private static final Set<String> INDEX_TYPES = Set.of("INDEX", "IDX", "AMXIDX");
    private static final Set<String> ALLOWED_BAR_SOURCES =
        Set.of("ANGEL_ONE_SMARTAPI", "UPSTOX_ANALYTICS");
    private static final MathContext MC = MathContext.DECIMAL64;

    private final CustomerFnoDiscoveryController discovery;
    private final InstrumentMasterService instruments;
    private final MarketDataRepository candles;
    private final UpstoxAnalyticsSource analytics;
    private final Clock clock;
    private volatile IndexHistory cachedIndexHistory;

    private record IndexHistory(Instant fetchedAt, List<UpstoxAnalyticsSource.Candle> bars) {}

    public CustomerMindFnoResearchController(CustomerFnoDiscoveryController discovery,
            InstrumentMasterService instruments, MarketDataRepository candles,
            UpstoxAnalyticsSource analytics, Clock clock) {
        this.discovery = discovery;
        this.instruments = instruments;
        this.candles = candles;
        this.analytics = analytics;
        this.clock = clock;
    }

    public record FnoResearch(String contractVersion, Instant evaluatedAt,
        String underlyingKey, String status, String signal, String reasonCode,
        String strategyId, String candleSource, int completedCandles,
        Instant latestCompletedCloseAt, String quoteSource, LocalDate expiry,
        BigDecimal strike, String instrumentKey, Integer lotSize,
        BigDecimal bid, BigDecimal ask, BigDecimal ltp, Long openInterest,
        BigDecimal iv, BigDecimal delta, BigDecimal theta, BigDecimal gamma, BigDecimal vega,
        Instant exchangeAt, Instant observedAt,
        boolean executable, boolean orderPlaced) {}

    @GetMapping("/fno-evaluation")
    public ResponseEntity<FnoResearch> evaluate(Authentication authentication) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
            .body(inspect(authentication));
    }

    FnoResearch inspect(Authentication authentication) {
        return inspectInternal(authentication, false);
    }

    /** Worker may call this only after an owner-scoped, opted-in PAPER room is verified. */
    FnoResearch inspectForOwnedPaperWorker() {
        return inspectInternal(null, true);
    }

    private FnoResearch inspectInternal(Authentication authentication, boolean worker) {
        Instant now = clock.instant();
        // This call performs the existing Cognito customer/active-account check.
        var cap = worker ? discovery.internalCapability()
            : discovery.capability(authentication).getBody();
        if (cap == null || !cap.sourceReady()) return blocked(now, "FNO_SOURCE_UNAVAILABLE", 0, null, null);
        if (!cap.displayRightsVerified() || !cap.contractDiscoveryVerified())
            return blocked(now, "FNO_DISPLAY_RIGHTS_NOT_VERIFIED", 0, null, null);
        if (!cap.feedConfigured()) return blocked(now, "FNO_V3_NOT_CONFIGURED", 0, null, null);
        if (!cap.marketOpen()) return noTrade(now, "MARKET_CLOSED", 0, null, null);

        // Dates are read from the actual Upstox source, never computed from a calendar.
        CustomerPaperFnoMarketController.Expiries expiryListing;
        try {
            expiryListing = worker ? discovery.internalExpiries()
                : discovery.expiries(authentication).getBody();
        } catch (ResponseStatusException ex) {
            if (ex.getStatusCode().value() == 401 || ex.getStatusCode().value() == 403) throw ex;
            return blocked(now, "FNO_EXPIRY_SOURCE_UNAVAILABLE", 0, null, null);
        }
        if (expiryListing == null || !"VERIFIED".equals(expiryListing.status())
                || !UNDERLYING.equals(expiryListing.underlyingKey())
                || expiryListing.expiries() == null || expiryListing.expiries().isEmpty())
            return blocked(now, "FNO_LISTED_EXPIRIES_UNAVAILABLE", 0, null, null);
        LocalDate today = now.atZone(IST).toLocalDate();
        Optional<LocalDate> expiry = expiryListing.expiries().stream()
            .filter(d -> d != null && !d.isBefore(today) && !d.isAfter(today.plusDays(90)))
            .sorted().findFirst();
        if (expiry.isEmpty()) return blocked(now, "FNO_LISTED_EXPIRIES_UNAVAILABLE", 0, null, null);

        // Exact, active NIFTY index identity. Never substitute NIFTY futures
        // or an arbitrary cash equity instrument for underlying index candles.
        Instrument index = findNiftyIndex();
        List<MarketCandle> stored = List.of();
        if (index != null) {
            try {
                stored = candles.recentCandles(index.id(), "ONE_DAY", 64);
            } catch (RuntimeException unavailable) {
                // The existing index store is optional. The Upstox source is
                // independently queried below; no fabricated fallback bars.
            }
        }
        var completed = new ArrayList<FnoLongOptionCrossoverV1.CompletedBar>();
        String candleSource = null;
        Instant latestClose = null;
        LocalDate previousSession = null;
        if (stored.size() >= 35) for (MarketCandle row : stored) {
            if (row == null || row.candleTime() == null || row.fetchedAt() == null
                    || row.close() == null || row.close().signum() <= 0
                    || row.instrumentId() != index.id()
                    || !"NSE".equals(row.exchange())
                    || !"ONE_DAY".equals(row.interval())
                    || row.source() == null || !ALLOWED_BAR_SOURCES.contains(row.source())
                    || (candleSource != null && !candleSource.equals(row.source())))
                return blocked(now, "NIFTY_CANDLE_PROVENANCE_INVALID", completed.size(), latestClose, candleSource);
            LocalDate day = row.candleTime().atZone(IST).toLocalDate();
            Instant closeAt = day.atTime(SESSION_CLOSE).atZone(IST).toInstant();
            if (day.equals(previousSession) || (latestClose != null && !closeAt.isAfter(latestClose))
                    || row.candleTime().isAfter(closeAt) || row.fetchedAt().isBefore(closeAt)
                    || row.fetchedAt().isAfter(now) || closeAt.isAfter(now))
                return blocked(now, "NIFTY_CANDLE_NOT_COMPLETED_AS_OF", completed.size(), latestClose, candleSource);
            completed.add(new FnoLongOptionCrossoverV1.CompletedBar(closeAt, row.fetchedAt(), row.close()));
            candleSource = row.source();
            latestClose = closeAt;
            previousSession = day;
        }
        if (completed.isEmpty()) {
            // Many existing Angel One archives contain NSE_EQ equities only.
            // Read NIFTY index history directly from the SAME approved Upstox
            // Analytics Token source as the option chain. No persistence.
            final List<UpstoxAnalyticsSource.Candle> sourceBars;
            try {
                sourceBars = fetchIndexBars(now);
            } catch (RuntimeException unavailable) {
                return blocked(now, "NIFTY_INDEX_PROVIDER_UNAVAILABLE", 0, null, null);
            }
            for (var row : sourceBars) {
                if (row == null || row.time() == null || row.close() == null
                        || row.close().signum() <= 0)
                    return blocked(now, "NIFTY_INDEX_PROVIDER_BAR_INVALID",
                        completed.size(), latestClose, candleSource);
                LocalDate day = row.time().atZone(IST).toLocalDate();
                Instant closeAt = day.atTime(SESSION_CLOSE).atZone(IST).toInstant();
                if (closeAt.isAfter(now)) continue; // Never treat today's open bar as complete.
                if (latestClose != null && !closeAt.isAfter(latestClose)
                        || row.time().isAfter(closeAt))
                    return blocked(now, "NIFTY_INDEX_PROVIDER_BAR_INVALID",
                        completed.size(), latestClose, candleSource);
                completed.add(new FnoLongOptionCrossoverV1.CompletedBar(closeAt, now, row.close()));
                latestClose = closeAt;
            }
            candleSource = completed.isEmpty() ? null : "UPSTOX_ANALYTICS";
        }
        if (completed.size() < 35)
            return blocked(now, "NIFTY_INDEX_HISTORY_INSUFFICIENT",
                completed.size(), latestClose, candleSource);
        var decision = FnoLongOptionCrossoverV1.evaluate(UNDERLYING,
            candleSource != null, List.copyOf(completed), now);
        if (decision.status() == FnoLongOptionCrossoverV1.Status.BLOCKED_DATA ||
                decision.status() == FnoLongOptionCrossoverV1.Status.BLOCKED_RIGHTS)
            return blocked(now, decision.reason(), completed.size(), latestClose, candleSource);
        if (decision.status() == FnoLongOptionCrossoverV1.Status.NO_CROSSOVER)
            return noTrade(now, decision.reason(), completed.size(), latestClose, candleSource);

        // Only after a real crossover, inspect the SAME real source/expiry/quotes
        // visible in Markets. No REST chain prices or synthesized order quotes.
        CustomerPaperFnoMarketController.Market market;
        try {
            market = worker ? discovery.internalMarket(expiry.get())
                : discovery.market(expiry.get(), authentication).getBody();
        } catch (ResponseStatusException ex) {
            if (ex.getStatusCode().value() == 401 || ex.getStatusCode().value() == 403) throw ex;
            return blocked(now, "FNO_QUOTE_SOURCE_UNAVAILABLE", completed.size(), latestClose, candleSource);
        }
        if (market == null || !UNDERLYING.equals(market.underlyingKey())
                || !expiry.get().equals(market.expiry())
                || !"UPSTOX_ANALYTICS".equals(market.source())
                || !"FRESH_QUOTES".equals(market.status()) || !market.displayRightsVerified()
                || market.liveOrderEnabled() || market.strikes() == null)
            return blocked(now, "FNO_FRESH_QUOTE_UNAVAILABLE", completed.size(), latestClose, candleSource);

        // A small call/put mid-price gap is only an ATM estimate, not an
        // independently observed index spot or a claim of optimal strike.
        var selected = market.strikes().stream()
            .filter(s -> s != null && s.strikePrice() != null && s.strikePrice().signum() > 0
                && fresh(s.call(), now) && fresh(s.put(), now)
                && s.call().lotSize() == s.put().lotSize())
            .min(Comparator.comparing(s -> midpoint(s.call()).subtract(midpoint(s.put())).abs()))
            .orElse(null);
        if (selected == null) return blocked(now, "FNO_PAIRED_FRESH_QUOTES_UNAVAILABLE",
            completed.size(), latestClose, candleSource);
        var leg = "CE".equals(decision.optionType()) ? selected.call() : selected.put();
        if (!decision.optionType().equals(leg.side()))
            return blocked(now, "FNO_CONTRACT_SIDE_MISMATCH", completed.size(), latestClose, candleSource);
        return new FnoResearch("mind.fno-research.v1", now, UNDERLYING,
            "RESEARCH_CANDIDATE", "CE".equals(leg.side()) ? "BUY_CALL_RESEARCH" : "BUY_PUT_RESEARCH",
            decision.reason(), decision.strategyId(), candleSource, completed.size(),
            latestClose, "UPSTOX_V3_EXCHANGE_TIMED", expiry.get(), selected.strikePrice(),
            leg.instrumentKey(), leg.lotSize(), leg.bid(), leg.ask(), leg.ltp(), leg.openInterest(),
            leg.iv(), leg.delta(), leg.theta(), leg.gamma(), leg.vega(),
            leg.exchangeAt(), leg.observedAt(), false, false);
    }

    /** One bounded index history read per five minutes, shared by all customers. */
    private synchronized List<UpstoxAnalyticsSource.Candle> fetchIndexBars(Instant now) {
        IndexHistory cache = cachedIndexHistory;
        if (cache != null && !now.isBefore(cache.fetchedAt())
                && now.isBefore(cache.fetchedAt().plus(Duration.ofMinutes(5)))) {
            return cache.bars();
        }
        LocalDate today = now.atZone(IST).toLocalDate();
        List<UpstoxAnalyticsSource.Candle> received =
            analytics.historicalNiftyIndexDailyCandles(today.minusDays(110), today);
        if (received == null || received.size() > 120) throw new IllegalStateException("NIFTY_INDEX_BAD_HISTORY");
        List<UpstoxAnalyticsSource.Candle> copy = List.copyOf(received);
        cachedIndexHistory = new IndexHistory(now, copy);
        return copy;
    }

    private Instrument findNiftyIndex() {
        for (String name : List.of("Nifty 50", "NIFTY 50", "NIFTY")) {
            Optional<Instrument> match = instruments.activeInstrumentBySymbol("NSE", name);
            if (match.isPresent()) {
                Instrument value = match.get();
                if ("NSE_CM".equals(value.exchangeSegment()) && value.expiry() == null
                        && value.instrumentType() != null
                        && INDEX_TYPES.contains(value.instrumentType().toUpperCase(java.util.Locale.ROOT)))
                    return value;
            }
        }
        return null;
    }

    private static BigDecimal midpoint(CustomerPaperFnoMarketController.Leg leg) {
        return leg.bid().add(leg.ask(), MC).divide(BigDecimal.valueOf(2), MC);
    }

    private static boolean fresh(CustomerPaperFnoMarketController.Leg leg, Instant now) {
        if (leg == null || !leg.quoteFresh() || leg.exchangeAt() == null || leg.observedAt() == null
                || leg.bid() == null || leg.ask() == null || leg.bid().signum() <= 0
                || leg.ask().compareTo(leg.bid()) < 0 || leg.lotSize() < 1
                || leg.lotSize() > 10000 || leg.instrumentKey() == null
                || !leg.instrumentKey().matches("NSE_FO\\|[A-Za-z0-9._-]{1,64}")
                || !Set.of("CE", "PE").contains(leg.side()))
            return false;
        if (leg.exchangeAt().isAfter(now) || leg.observedAt().isAfter(now)
                || leg.observedAt().isBefore(leg.exchangeAt())) return false;
        return Duration.between(leg.exchangeAt(), now).compareTo(MAX_QUOTE_AGE) <= 0
            && Duration.between(leg.observedAt(), now).compareTo(MAX_QUOTE_AGE) <= 0;
    }

    private static FnoResearch blocked(Instant now, String reason, int bars, Instant closedAt, String candleSource) {
        return base(now, "BLOCKED", "NO_TRADE", reason, bars, closedAt, candleSource);
    }

    private static FnoResearch noTrade(Instant now, String reason, int bars, Instant closedAt, String candleSource) {
        return base(now, "NO_TRADE", "NO_TRADE", reason, bars, closedAt, candleSource);
    }

    private static FnoResearch base(Instant now, String status, String signal,
            String reason, int bars, Instant closedAt, String candleSource) {
        return new FnoResearch("mind.fno-research.v1", now, UNDERLYING, status, signal,
            reason, null, candleSource, bars, closedAt,
            null, null, null, null, null, null, null, null, null, null, null,
            null, null, null, null, null, false, false);
    }
}
