package in.aitradingsystem.marketdata;

import in.aitradingsystem.instrument.InstrumentMasterException;
import in.aitradingsystem.user.CustomerIdentityStore;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * Read-only stored historical candles for the customer stock-detail chart.
 * Candles come from the persisted catalogue store, never from a live provider call.
 * Broker symbol tokens, catalogue versions and operator fields are not returned.
 */
@RestController
@RequestMapping("/api/customer/market-data/candles")
public class CustomerMarketCandleController {
    private final CustomerIdentityStore identities;
    private final HistoricalCandleService candles;

    @Autowired
    public CustomerMarketCandleController(CustomerIdentityStore identities,
            HistoricalCandleService candles) {
        this.identities = identities;
        this.candles = candles;
    }

    public record CustomerCandle(Instant candleTime, BigDecimal open, BigDecimal high,
                                 BigDecimal low, BigDecimal close, long volume,
                                 String source, Instant fetchedAt) {}

    public record CustomerCandlePage(long instrumentId, String interval, List<CustomerCandle> items,
                                     int page, int size, long totalItems, boolean hasNext) {}

    @GetMapping
    public ResponseEntity<CustomerCandlePage> candles(
            Authentication authentication,
            @RequestParam long instrumentId,
            @RequestParam HistoricalCandleInterval interval,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate fromDate,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate toDate,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "200") int size) {
        requireCustomer(authentication);
        CandlePage result = candles.query(instrumentId, interval, fromDate, toDate, page, size);
        List<CustomerCandle> items = result.items().stream()
            .map(c -> new CustomerCandle(c.candleTime(), c.open(), c.high(), c.low(), c.close(),
                c.volume(), c.source(), c.fetchedAt()))
            .toList();
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(
            new CustomerCandlePage(instrumentId, interval.name(), items,
                result.page(), result.size(), result.totalItems(), result.hasNext()));
    }

    private void requireCustomer(Authentication authentication) {
        if (!(authentication instanceof JwtAuthenticationToken token) ||
                token.getAuthorities().stream().noneMatch(a -> "ROLE_CUSTOMER".equals(a.getAuthority()))) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
        }
        boolean active = identities.find("cognito", token.getToken().getIssuer().toString(),
                token.getToken().getSubject())
            .filter(a -> "ACTIVE".equals(a.status()) && "CUSTOMER".equals(a.role())).isPresent();
        if (!active) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
    }

    @ExceptionHandler(InstrumentMasterException.class)
    ProblemDetail instrumentFailure(InstrumentMasterException ex) {
        HttpStatus status = "INSTRUMENT_NOT_FOUND".equals(ex.code())
            ? HttpStatus.NOT_FOUND : HttpStatus.UNPROCESSABLE_ENTITY;
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, ex.getMessage());
        problem.setProperty("code", ex.code());
        return problem;
    }

    @ExceptionHandler(MarketDataException.class)
    ProblemDetail candleFailure(MarketDataException ex) {
        HttpStatus status = switch (ex.code()) {
            case "INVALID_INSTRUMENT_ID", "INVALID_INTERVAL", "INVALID_PAGE",
                 "INVALID_PAGE_SIZE", "INVALID_DATE_RANGE", "FUTURE_DATE_NOT_ALLOWED",
                 "DATE_RANGE_TOO_LARGE" -> HttpStatus.BAD_REQUEST;
            default -> HttpStatus.SERVICE_UNAVAILABLE;
        };
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status,
            status == HttpStatus.BAD_REQUEST ? ex.getMessage() : "Stored candles are unavailable");
        problem.setProperty("code", ex.code());
        return problem;
    }
}
