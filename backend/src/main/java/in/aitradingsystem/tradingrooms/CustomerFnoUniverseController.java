package in.aitradingsystem.tradingrooms;

import in.aitradingsystem.provider.upstox.UpstoxFnoInstrumentUniverse;
import java.time.LocalDate;
import java.util.List;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * Customer-visible catalog identity coverage. Not a price source, quote,
 * websocket subscription, broker permission or order entry surface.
 *
 * Full NSE_FO is paginated, never serialized into an unbounded HTTP response.
 * Mintick Mind can distinguish known-but-not-subscribed identities from a
 * missing instrument without silently asserting realtime quote coverage.
 */
@RestController
@RequestMapping("/api/customer/markets/fno/universe")
public final class CustomerFnoUniverseController {
    private final FnoFuturesContractRegistry registry;

    public CustomerFnoUniverseController(FnoFuturesContractRegistry registry) {
        this.registry = registry;
    }

    public record Contracts(String provenance, boolean hasRealtimeQuotes,
            boolean liveOrderAuthorized,
            FnoFuturesContractRegistry.UniverseStatus catalog,
            int page, int pageSize, int totalMatches,
            List<UpstoxFnoInstrumentUniverse.Contract> contracts) {}

    @GetMapping("/status")
    public ResponseEntity<FnoFuturesContractRegistry.UniverseStatus> status(
            Authentication auth) {
        requireCustomer(auth);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
            .body(registry.universeStatus());
    }

    @GetMapping("/contracts")
    public ResponseEntity<Contracts> contracts(Authentication auth,
            @RequestParam(value = "underlyingKey", required = false)
            String underlyingKey,
            @RequestParam(value = "expiry", required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate expiry,
            @RequestParam(value = "type", required = false) String type,
            @RequestParam(value = "page", defaultValue = "0") int page,
            @RequestParam(value = "size", defaultValue = "50") int size) {
        requireCustomer(auth);
        if (page < 0 || size < 1 || size > 100 ||
                (underlyingKey != null && !underlyingKey.matches(
                    "(?:NSE_EQ\\|[A-Za-z0-9._-]{1,64}|NSE_INDEX\\|[A-Za-z0-9 ._-]{1,64})")) ||
                (type != null && !List.of("CE", "PE", "FUT").contains(type))) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "Invalid F&O universe query");
        }
        var result = registry.universe(underlyingKey, expiry, type, page, size);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
            .body(new Contracts("UPSTOX_BOD_INSTRUMENT_MASTER", false, false,
                result.status(), page, size, result.totalMatches(),
                result.contracts()));
    }

    @GetMapping("/search")
    public ResponseEntity<Contracts> search(Authentication auth,
            @RequestParam("q") String query,
            @RequestParam(value = "type", required = false) String type,
            @RequestParam(value = "page", defaultValue = "0") int page,
            @RequestParam(value = "size", defaultValue = "50") int size) {
        requireCustomer(auth);
        if (query == null || query.isBlank() || query.length() > 64 ||
                !query.matches("[A-Za-z0-9 ._-]+") ||
                (type != null && !List.of("FUT", "CE", "PE").contains(type)) ||
                page < 0 || size < 1 || size > 100) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "Invalid F&O universe search");
        }
        var result = registry.universeSearch(query, type, page, size);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
            .body(new Contracts("UPSTOX_BOD_INSTRUMENT_MASTER", false, false,
                result.status(), page, size, result.totalMatches(),
                result.contracts()));
    }

    private static void requireCustomer(Authentication auth) {
        if (!(auth instanceof JwtAuthenticationToken token) ||
                token.getAuthorities().stream().noneMatch(
                    a -> "ROLE_CUSTOMER".equals(a.getAuthority()))) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED,
                "Customer authorization required");
        }
    }
}
