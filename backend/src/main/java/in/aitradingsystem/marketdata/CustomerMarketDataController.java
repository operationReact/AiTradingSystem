package in.aitradingsystem.marketdata;

import in.aitradingsystem.instrument.Instrument;
import in.aitradingsystem.provider.angelone.CustomerAngelOneSessionService;
import in.aitradingsystem.provider.angelone.CustomerAngelOneLiveQuoteService;
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
    private final CustomerIdentityStore identities;
    private final InstrumentMasterService instruments;
    private final MarketDataProvider market;
    private final String ownerSubject;
    private final String providerMode;
    private final CustomerAngelOneSessionService customerBroker;
    private final CustomerAngelOneLiveQuoteService liveQuotes;

    /** Existing five-argument constructor retained for deterministic owner-only unit tests. */
    CustomerMarketDataController(CustomerIdentityStore identities,
            InstrumentMasterService instruments, MarketDataProvider market,
            String ownerSubject, String providerMode) {
        this(identities, instruments, market, ownerSubject, providerMode, null, null);
    }

    CustomerMarketDataController(CustomerIdentityStore identities,
            InstrumentMasterService instruments, MarketDataProvider market,
            String ownerSubject, String providerMode,
            CustomerAngelOneSessionService customerBroker) {
        this(identities, instruments, market, ownerSubject, providerMode, customerBroker, null);
    }

    @Autowired
    public CustomerMarketDataController(CustomerIdentityStore identities,
            InstrumentMasterService instruments, MarketDataProvider market,
            @Value("${MINTTICK_ANGEL_ONE_OWNER_SUB:}") String ownerSubject,
            @Value("${app.market-data.provider:ANGEL_ONE}") String providerMode,
            CustomerAngelOneSessionService customerBroker,
            CustomerAngelOneLiveQuoteService liveQuotes) {
        this.identities = identities;
        this.instruments = instruments;
        this.market = market;
        this.ownerSubject = ownerSubject == null ? "" : ownerSubject.trim();
        this.providerMode = providerMode;
        this.customerBroker = customerBroker;
        this.liveQuotes = liveQuotes;
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
        JwtAuthenticationToken token = requireCustomer(authentication);
        boolean ownerFeed = ownerSubject.equals(token.getToken().getSubject())
            && !ownerSubject.isEmpty() && "ANGEL_ONE_LAMBDA".equals(providerMode);
        var account = identities.find("cognito", token.getToken().getIssuer().toString(),
            token.getToken().getSubject()).orElseThrow(
                () -> new ResponseStatusException(HttpStatus.UNAUTHORIZED));
        // A missing, revoked or expired broker session is an unavailable feed,
        // never a null-pointer 500 or a reason to fall back to a different user.
        var connection = customerBroker == null ? null : customerBroker.status(account.id());
        boolean accountFeed = connection != null && connection.connected()
            && connection.expiresAt() != null && connection.expiresAt().isAfter(Instant.now());
        if (!ownerFeed && !accountFeed) {
            if (ownerSubject.equals(token.getToken().getSubject())
                    && !"ANGEL_ONE_LAMBDA".equals(providerMode)) {
                throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    "Private read-only market feed has not been enabled");
            }
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                "Connect your own brokerage account to request market quotes");
        }
        Instrument instrument = instruments.requireActiveInstrument(instrumentId);
        if (!java.util.Set.of("NSE_CM", "BSE_CM").contains(instrument.exchangeSegment())
                || !java.util.Set.of("NSE", "BSE").contains(instrument.exchange())
                || !instrument.tradingSymbol().endsWith("-EQ")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Cash equities only");
        }
        // Only manually connected customer sessions can stream: the SSM Lambda
        // path deliberately does not release provider JWT or feedToken to EC2.
        if (live) {
            if (liveQuotes == null || !accountFeed)
                throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    "Live socket requires a manually connected Angel One session");
            var tick = liveQuotes.quote(account.id(), instrument);
            // Recheck instrument/source identity at the customer API boundary,
            // not only inside the broker decoder. Never re-label a mismatched
            // or fabricated sample as a valid customer's LIVE quote.
            boolean matches = tick != null && tick.instrumentId() == instrument.id()
                && instrument.exchange().equals(tick.exchange())
                && instrument.tradingSymbol().equals(tick.tradingSymbol());
            boolean verifiedLive = tick != null && "LIVE".equals(tick.status())
                && "ANGEL_ONE_SMARTAPI_WS_V2".equals(tick.source())
                && tick.ltp() != null && tick.ltp().signum() > 0
                && tick.exchangeAt() != null && tick.receivedAt() != null;
            boolean emptyState = tick != null && java.util.Set.of(
                "CONNECTING", "RECONNECTING", "STALE", "UNAVAILABLE",
                "MARKET_CLOSED", "BROKER_CONNECTION_REQUIRED").contains(tick.status())
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
        BigDecimal price = ownerFeed ? market.lastTradedPrice(instrument)
            : customerBroker.quote(account.id(), instrument);
        String source = ownerFeed ? market.source() : "ANGEL_ONE_SMARTAPI_CUSTOMER";
        return noStore(new Quote(instrument.id(), instrument.exchange(), instrument.tradingSymbol(),
            price, Instant.now(), source));
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
}
