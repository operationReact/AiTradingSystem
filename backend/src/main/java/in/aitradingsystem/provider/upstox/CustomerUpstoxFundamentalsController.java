package in.aitradingsystem.provider.upstox;

import com.fasterxml.jackson.databind.JsonNode;
import in.aitradingsystem.provider.upstox.UpstoxApiClient.UpstoxProviderException;
import in.aitradingsystem.user.CustomerIdentityStore;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * Customer-authenticated, read-only company fundamentals for exactly matched
 * active NSE cash equities. Source: Upstox official ISIN-keyed financial APIs.
 * Never match by company name, accept caller ISIN, or expose OAuth credentials.
 */
@RestController
@RequestMapping("/api/customer/market-data")
public final class CustomerUpstoxFundamentalsController {
    private final CustomerIdentityStore identities;
    private final JdbcTemplate jdbc;
    private final UpstoxNseEquityIsinSource isinSource;
    private final CustomerUpstoxOAuthService upstox;

    public CustomerUpstoxFundamentalsController(CustomerIdentityStore identities,
            JdbcTemplate jdbc, UpstoxNseEquityIsinSource isinSource,
            CustomerUpstoxOAuthService upstox) {
        this.identities = identities;
        this.jdbc = jdbc;
        this.isinSource = isinSource;
        this.upstox = upstox;
    }

    private record Listing(long id, String exchange, String symbol) {}
    public record Fundamentals(String contractVersion, long instrumentId,
            String tradingSymbol, String isin, String section,
            String provider, Instant fetchedAt, JsonNode data) {}

    @GetMapping("/fundamentals")
    public ResponseEntity<Fundamentals> get(Authentication authentication,
            @RequestParam long instrumentId, @RequestParam String section) {
        UUID customerId = customer(authentication);
        if (instrumentId < 1 || !List.of("profile", "ratios", "income", "shareholding").contains(section)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid fundamentals request");
        }
        List<Listing> listings = jdbc.query("""
            SELECT i.instrument_id, i.exchange, i.trading_symbol
              FROM instrument_master i
              JOIN instrument_catalogue_state s ON s.singleton_id=1
                AND s.active_version=i.catalogue_version
             WHERE i.active=TRUE AND i.instrument_id=?
               AND i.exchange='NSE' AND i.exchange_segment='NSE_CM'
               AND i.trading_symbol LIKE '%-EQ'
             LIMIT 1
            """, (rs, row) -> new Listing(rs.getLong(1), rs.getString(2),
                rs.getString(3)), instrumentId);
        if (listings.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Active NSE equity not found");
        }
        Listing stock = listings.get(0);
        String isin = isinSource.resolve(stock.exchange(), stock.symbol())
            .orElseThrow(() -> new ResponseStatusException(
                HttpStatus.SERVICE_UNAVAILABLE,
                "Verified NSE equity ISIN reference is unavailable"));
        var response = upstox.fundamentals(customerId, isin, section);
        // UpstoxApiClient has already verified the provider's success envelope.
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
            .body(new Fundamentals("customer.market.fundamentals.v1",
                stock.id(), stock.symbol(), isin, section, response.provider(),
                response.fetchedAt(), response.data()));
    }

    private UUID customer(Authentication authentication) {
        if (!(authentication instanceof JwtAuthenticationToken jwt)
                || jwt.getAuthorities().stream().noneMatch(
                    authority -> "ROLE_CUSTOMER".equals(authority.getAuthority()))) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Customer session required");
        }
        return identities.find("cognito", jwt.getToken().getIssuer().toString(),
                jwt.getToken().getSubject())
            .filter(account -> "ACTIVE".equals(account.status()) &&
                "CUSTOMER".equals(account.role()))
            .map(CustomerIdentityStore.CustomerAccount::id)
            .orElseThrow(() -> new ResponseStatusException(
                HttpStatus.UNAUTHORIZED, "Customer account not linked"));
    }

    @ExceptionHandler(UpstoxProviderException.class)
    ProblemDetail upstoxUnavailable(UpstoxProviderException error) {
        HttpStatus status = switch (error.code()) {
            case "UPSTOX_NOT_CONNECTED" -> HttpStatus.CONFLICT;
            case "UPSTOX_FUNDAMENTAL_SECTION_INVALID",
                 "UPSTOX_FUNDAMENTAL_ISIN_INVALID" -> HttpStatus.BAD_REQUEST;
            default -> HttpStatus.SERVICE_UNAVAILABLE;
        };
        ProblemDetail result = ProblemDetail.forStatusAndDetail(
            status, "Verified Upstox fundamentals could not be loaded");
        result.setProperty("code", error.code());
        return result;
    }
}
