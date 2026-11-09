package in.aitradingsystem.marketdata;

import in.aitradingsystem.instrument.Instrument;
import in.aitradingsystem.provider.angelone.AngelOneException;
import in.aitradingsystem.provider.angelone.AngelOneQuoteDetailParser;
import in.aitradingsystem.provider.angelone.CustomerAngelOneSessionService;
import in.aitradingsystem.provider.angelone.CustomerAngelOneLiveQuoteService;
import in.aitradingsystem.provider.angelone.AppAngelOneMarketFeedService;
import in.aitradingsystem.instrument.InstrumentMasterException;
import in.aitradingsystem.instrument.InstrumentMasterService;
import in.aitradingsystem.instrument.InstrumentMasterRepository;
import in.aitradingsystem.instrument.InstrumentSearchResponse;
import in.aitradingsystem.user.CustomerIdentityStore;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import jakarta.validation.constraints.Pattern;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * Customer market catalogue, and an owner-gated read-only Angel One quote pilot.
 * No public market-data redistribution, raw broker symbol tokens, streaming
 * credentials or order-management permissions are exposed.
 */
@RestController
@Validated
@RequestMapping("/api/customer/market-data")
public class CustomerMarketDataController {
    /** Matches the catalogue page size cap so one visible page is one snapshot request. */
    static final int MAX_SNAPSHOT_ROWS = 30;
    private final CustomerIdentityStore identities;
    private final InstrumentMasterService instruments;
    private final MarketDataProvider market;
    private final String ownerSubject;
    private final String providerMode;
    private final CustomerAngelOneSessionService customerBroker;
    private final CustomerAngelOneLiveQuoteService liveQuotes;
    private final AppAngelOneMarketFeedService applicationFeed;
    private final MarketOverviewService marketOverview;
    private final MarketLastTickStore lastTickStore;

    /** Existing five-argument constructor retained for deterministic owner-only unit tests. */
    CustomerMarketDataController(CustomerIdentityStore identities,
            InstrumentMasterService instruments, MarketDataProvider market,
            String ownerSubject, String providerMode) {
        this(identities, instruments, market, ownerSubject, providerMode, null, null, null, null, null);
    }

    CustomerMarketDataController(CustomerIdentityStore identities,
            InstrumentMasterService instruments, MarketDataProvider market,
            String ownerSubject, String providerMode,
            CustomerAngelOneSessionService customerBroker) {
        this(identities, instruments, market, ownerSubject, providerMode, customerBroker, null, null, null, null);
    }

    CustomerMarketDataController(CustomerIdentityStore identities,
            InstrumentMasterService instruments, MarketDataProvider market,
            String ownerSubject, String providerMode,
            CustomerAngelOneSessionService customerBroker,
            CustomerAngelOneLiveQuoteService liveQuotes) {
        this(identities, instruments, market, ownerSubject, providerMode,
            customerBroker, liveQuotes, null, null, null);
    }

    CustomerMarketDataController(CustomerIdentityStore identities,
            InstrumentMasterService instruments, MarketDataProvider market,
            String ownerSubject, String providerMode,
            CustomerAngelOneSessionService customerBroker,
            CustomerAngelOneLiveQuoteService liveQuotes,
            AppAngelOneMarketFeedService applicationFeed) {
        this(identities, instruments, market, ownerSubject, providerMode,
            customerBroker, liveQuotes, applicationFeed, null, null);
    }

    @Autowired
    public CustomerMarketDataController(CustomerIdentityStore identities,
            InstrumentMasterService instruments, MarketDataProvider market,
            @Value("${MINTTICK_ANGEL_ONE_OWNER_SUB:}") String ownerSubject,
            @Value("${app.market-data.provider:ANGEL_ONE}") String providerMode,
            CustomerAngelOneSessionService customerBroker,
            CustomerAngelOneLiveQuoteService liveQuotes,
            AppAngelOneMarketFeedService applicationFeed,
            MarketOverviewService marketOverview,
            MarketLastTickStore lastTickStore) {
        this.identities = identities;
        this.instruments = instruments;
        this.market = market;
        this.ownerSubject = ownerSubject == null ? "" : ownerSubject.trim();
        this.providerMode = providerMode;
        this.customerBroker = customerBroker;
        this.liveQuotes = liveQuotes;
        this.applicationFeed = applicationFeed;
        this.marketOverview = marketOverview;
        this.lastTickStore = lastTickStore;
    }


    @GetMapping("/instruments/status")
    public ResponseEntity<InstrumentMasterRepository.CatalogueSummary> instrumentStatus(
            Authentication authentication) {
        requireCustomer(authentication);
        return noStore(instruments.catalogueSummary());
    }

    @GetMapping("/instruments")
    public ResponseEntity<InstrumentSearchResponse> search(
            Authentication authentication,
            @RequestParam(required = false) @Size(max = 100)
                @Pattern(regexp = "[A-Za-z0-9 .&/_-]*") String query,
            @RequestParam(required = false) @Pattern(regexp = "NSE|BSE") String exchange,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) int size) {
        requireCustomer(authentication);
        if (page > 10000 || size > 30)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid catalogue page");
        return noStore(instruments.search(query, exchange, "EQ", page, size));
    }

    public record Quote(long instrumentId, String exchange, String tradingSymbol,
                        BigDecimal ltp, Instant fetchedAt, String source,
                        Instant exchangeAt, String status) {
        public Quote(long instrumentId, String exchange, String tradingSymbol,
                BigDecimal ltp, Instant fetchedAt, String source) {
            this(instrumentId, exchange, tradingSymbol, ltp, fetchedAt, source, null, "ON_DEMAND");
        }
    }

    /** Existing code-level fixture; HTTP clients use the routed overload below. */
    ResponseEntity<Quote> quote(Authentication authentication, long instrumentId) {
        return quote(authentication, instrumentId, false);
    }

    @GetMapping("/quotes")
    public ResponseEntity<Quote> quote(Authentication authentication,
            @RequestParam @Min(1) long instrumentId,
            @RequestParam(defaultValue = "false") boolean live) {
        // A customer must authenticate to MintTick, NOT to Angel One, to
        // browse our private app-managed market feed.
        requireCustomer(authentication);
        Instrument instrument = requireCashEquity(instruments.requireActiveInstrument(instrumentId));

        if (live) {
            // Live ticks originate from ONE server-side SSM-backed app feed,
            // never the current user's linked brokerage account.
            if (applicationFeed == null || !applicationFeed.available()) {
                throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    "Application live market feed is unavailable");
            }
            var tick = applicationFeed.live(instrument);
            boolean matches = tick != null && tick.instrumentId() == instrument.id()
                && instrument.exchange().equals(tick.exchange())
                && instrument.tradingSymbol().equals(tick.tradingSymbol());
            boolean verifiedLive = tick != null && "LIVE".equals(tick.status())
                && "ANGEL_ONE_SMARTAPI_WS_V2".equals(tick.source())
                && tick.ltp() != null && tick.ltp().signum() > 0
                && tick.exchangeAt() != null && tick.receivedAt() != null;
            boolean emptyState = tick != null && java.util.Set.of(
                "CONNECTING", "RECONNECTING", "STALE", "UNAVAILABLE",
                "MARKET_CLOSED").contains(tick.status())
                && tick.ltp() == null && tick.exchangeAt() == null
                && tick.source() == null;
            if (!matches || !(verifiedLive || emptyState)) {
                throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    "Live provider identity or freshness evidence is unavailable");
            }
            return noStore(new Quote(instrument.id(), instrument.exchange(),
                instrument.tradingSymbol(), tick.ltp(), Instant.now(),
                tick.source(), tick.exchangeAt(), tick.status()));
        }

        // On-demand quotes share the same *application-owned* connection.
        // Lambda remains an optional SSM-managed backend fallback.
        BigDecimal price;
        String source;
        if (applicationFeed != null && applicationFeed.available()) {
            price = applicationFeed.quote(instrument);
            source = "ANGEL_ONE_SMARTAPI_APP";
        } else if ("ANGEL_ONE_LAMBDA".equals(providerMode)) {
            price = market.lastTradedPrice(instrument);
            source = market.source();
        } else {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                "Application market feed has not been configured");
        }
        if (price == null || price.signum() <= 0) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                "Application market feed returned no verified quote");
        }
        return noStore(new Quote(instrument.id(), instrument.exchange(),
            instrument.tradingSymbol(), price, Instant.now(), source));
    }

    public record SnapshotRow(long instrumentId, String exchange, String tradingSymbol,
                              BigDecimal ltp, Instant exchangeAt, String status, String source,
                              BigDecimal changePercent) {}

    /**
     * Day OHLC, previous close and traded volume from one verified FULL quote. {@code previousClose}
     * is the provider's previous-session close. {@code exchangeAt} is the exchange feed time, and
     * {@code fetchedAt} is when MintTick received the reply. The source tag names the provider path.
     */
    public record DetailQuote(long instrumentId, String exchange, String tradingSymbol,
                              BigDecimal ltp, BigDecimal open, BigDecimal high, BigDecimal low,
                              BigDecimal previousClose, long volume, Instant exchangeAt,
                              Instant fetchedAt, String source, String status,
                              BigDecimal averagePrice, BigDecimal week52High, BigDecimal week52Low,
                              long totalBuyQuantity, long totalSellQuantity,
                              List<AngelOneQuoteDetailParser.DepthLevel> bids,
                              List<AngelOneQuoteDetailParser.DepthLevel> asks) {}

    /** Application-feed OHLC and volume for one cash equity; no customer broker credentials used. */
    @GetMapping("/quotes/detail")
    public ResponseEntity<DetailQuote> quoteDetail(Authentication authentication,
            @RequestParam @Min(1) long instrumentId) {
        requireCustomer(authentication);
        Instrument instrument = requireCashEquity(instruments.requireActiveInstrument(instrumentId));
        if (applicationFeed == null || !applicationFeed.available()) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                "Application market feed has not been configured");
        }
        var detail = applicationFeed.detail(instrument);
        return noStore(new DetailQuote(instrument.id(), instrument.exchange(),
            instrument.tradingSymbol(), detail.ltp(), detail.open(), detail.high(), detail.low(),
            detail.previousClose(), detail.volume(), detail.exchangeAt(), Instant.now(),
            "ANGEL_ONE_SMARTAPI_FULL_APP", "ON_DEMAND", detail.averagePrice(), detail.week52High(),
            detail.week52Low(), detail.totalBuyQuantity(), detail.totalSellQuantity(),
            detail.bids(), detail.asks()));
    }

    public record Snapshot(Instant generatedAt, List<SnapshotRow> rows) {}

    /** Nifty 50 breadth, movers, gap, sectors and index values from one verified bulk provider read per batch. */
    @GetMapping("/overview")
    public ResponseEntity<MarketOverviewService.Overview> overview(Authentication authentication) {
        requireCustomer(authentication);
        if (marketOverview == null || applicationFeed == null || !applicationFeed.available()) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                "Application market feed has not been configured");
        }
        return noStore(marketOverview.overview());
    }


    /**
     * Live state for a visible page of cash equities, sharing one application stream.
     * Each row carries its own status; a price appears only when that row is verified LIVE.
     */
    @GetMapping("/snapshot")
    public ResponseEntity<Snapshot> snapshot(Authentication authentication,
            @RequestParam List<Long> instrumentIds) {
        requireCustomer(authentication);
        if (instrumentIds.isEmpty() || instrumentIds.size() > MAX_SNAPSHOT_ROWS
                || instrumentIds.stream().anyMatch(id -> id == null || id < 1)
                || instrumentIds.stream().distinct().count() != instrumentIds.size()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid market snapshot request");
        }
        List<Instrument> stocks = new ArrayList<>();
        for (long id : instrumentIds) {
            stocks.add(requireCashEquity(instruments.requireActiveInstrument(id)));
        }
        if (applicationFeed == null || !applicationFeed.available()) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                "Application live market feed is unavailable");
        }
        var replies = applicationFeed.snapshot(stocks);
        Map<Long, MarketLastTickStore.LastTick> lastTicks = lastTickStore == null
            ? Map.of() : lastTickStore.lastFor(stocks.stream().map(Instrument::id).toList());
        List<SnapshotRow> rows = new ArrayList<>();
        for (int i = 0; i < stocks.size(); i++) {
            rows.add(snapshotRow(stocks.get(i), replies.get(i), lastTicks.get(stocks.get(i).id())));
        }
        return noStore(new Snapshot(Instant.now(), rows));
    }

    /**
     * A closed-market row carries the last stored LIVE tick as LAST_CLOSE, labelled stored and
     * never as LIVE. The change is null until a previous session close has been stored.
     */
    private static SnapshotRow snapshotRow(Instrument instrument,
            CustomerAngelOneLiveQuoteService.LiveQuote reply, MarketLastTickStore.LastTick lastTick) {
        boolean matches = reply.instrumentId() == instrument.id()
            && instrument.exchange().equals(reply.exchange())
            && instrument.tradingSymbol().equals(reply.tradingSymbol());
        if (!matches) {
            return new SnapshotRow(instrument.id(), instrument.exchange(),
                instrument.tradingSymbol(), null, null, "UNAVAILABLE", null, null);
        }
        boolean verifiedLive = "LIVE".equals(reply.status())
            && "ANGEL_ONE_SMARTAPI_WS_V2".equals(reply.source())
            && reply.ltp() != null && reply.ltp().signum() > 0
            && reply.exchangeAt() != null && reply.receivedAt() != null;
        if (verifiedLive) {
            return new SnapshotRow(instrument.id(), instrument.exchange(),
                instrument.tradingSymbol(), reply.ltp(), reply.exchangeAt(), "LIVE", reply.source(), null);
        }
        if ("MARKET_CLOSED".equals(reply.status()) && lastTick != null) {
            return new SnapshotRow(instrument.id(), instrument.exchange(),
                instrument.tradingSymbol(), lastTick.ltp(), lastTick.exchangeAt(), "LAST_CLOSE",
                "STORED_LAST_LIVE_TICK", lastTick.changePercent());
        }
        String status = java.util.Set.of("CONNECTING", "RECONNECTING", "STALE", "UNAVAILABLE",
                "MARKET_CLOSED").contains(reply.status()) ? reply.status() : "UNAVAILABLE";
        return new SnapshotRow(instrument.id(), instrument.exchange(),
            instrument.tradingSymbol(), null, null, status, null, null);
    }

    private static Instrument requireCashEquity(Instrument instrument) {
        if (!java.util.Set.of("NSE_CM", "BSE_CM").contains(instrument.exchangeSegment())
                || !java.util.Set.of("NSE", "BSE").contains(instrument.exchange())
                || !instrument.tradingSymbol().endsWith("-EQ")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Cash equities only");
        }
        return instrument;
    }

    private JwtAuthenticationToken requireCustomer(Authentication authentication) {
        if (!(authentication instanceof JwtAuthenticationToken token) ||
                token.getAuthorities().stream().noneMatch(a -> "ROLE_CUSTOMER".equals(a.getAuthority()))) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
        }
        boolean active = identities.find("cognito", token.getToken().getIssuer().toString(),
                token.getToken().getSubject())
            .filter(a -> "ACTIVE".equals(a.status()) && "CUSTOMER".equals(a.role())).isPresent();
        if (!active) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
        return token;
    }

    private static <T> ResponseEntity<T> noStore(T response) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(response);
    }

    @ExceptionHandler(InstrumentMasterException.class)
    ProblemDetail catalogueFailure(InstrumentMasterException ex) {
        HttpStatus status = "INSTRUMENT_NOT_FOUND".equals(ex.code())
            ? HttpStatus.NOT_FOUND : HttpStatus.UNPROCESSABLE_ENTITY;
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, ex.getMessage());
        problem.setProperty("code", ex.code());
        return problem;
    }

    @ExceptionHandler(MarketDataException.class)
    ProblemDetail quoteFailure(MarketDataException ex) {
        HttpStatus status = "PROVIDER_RATE_LIMITED".equals(ex.code())
            ? HttpStatus.TOO_MANY_REQUESTS : HttpStatus.SERVICE_UNAVAILABLE;
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status,
            "Read-only market data is unavailable");
        problem.setProperty("code", ex.code());
        return problem;
    }

    /** Provider failures from the on-demand quote path; fixed messages avoid leaking provider detail. */
    @ExceptionHandler(AngelOneException.class)
    ProblemDetail providerFailure(AngelOneException ex) {
        HttpStatus status = switch (ex.kind()) {
            case RATE_LIMITED -> HttpStatus.TOO_MANY_REQUESTS;
            case NOT_CONFIGURED, UNAVAILABLE, SESSION_EXPIRED, REJECTED, INVALID_RESPONSE ->
                HttpStatus.SERVICE_UNAVAILABLE;
        };
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status,
            "Read-only market data is unavailable");
        problem.setProperty("code", "PROVIDER_" + ex.kind().name());
        return problem;
    }

    @ExceptionHandler(CustomerAngelOneSessionService.BrokerConnectionRequiredException.class)
    ProblemDetail brokerConnectionRequired() {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.SERVICE_UNAVAILABLE,
            "Read-only market data is unavailable");
        problem.setProperty("code", "BROKER_CONNECTION_REQUIRED");
        return problem;
    }
}
