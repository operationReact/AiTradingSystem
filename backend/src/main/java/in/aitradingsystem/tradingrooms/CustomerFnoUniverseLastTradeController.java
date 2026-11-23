package in.aitradingsystem.tradingrooms;

import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/** Owner-authenticated source last trade; never an OMS quote or order. */
@RestController
@RequestMapping("/api/customer/markets/fno/universe")
public final class CustomerFnoUniverseLastTradeController {
    private final FnoUniverseLastTradeService last;

    public CustomerFnoUniverseLastTradeController(FnoUniverseLastTradeService last) {
        this.last = last;
    }

    @GetMapping("/last-trade")
    public ResponseEntity<FnoUniverseLastTradeService.LastTrade> lastTrade(
            @RequestParam("instrumentKey") String instrumentKey,
            Authentication authentication) {
        if (!(authentication instanceof JwtAuthenticationToken token) ||
                token.getAuthorities().stream().noneMatch(
                    a -> "ROLE_CUSTOMER".equals(a.getAuthority()))) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED,
                "Customer authorization required");
        }
        if (instrumentKey == null ||
                !instrumentKey.matches("NSE_FO\\|[A-Za-z0-9._-]{1,64}")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "Invalid listed derivative key");
        }
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
            .body(last.read(instrumentKey));
    }
}
