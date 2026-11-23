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

/** Authenticated read-only evidence for Mintick Mind's per-leg source gates. */
@RestController
@RequestMapping("/api/customer/markets/fno/universe")
public final class CustomerFnoUniverseCoverageController {
    private final FnoUniverseCoverageService coverage;

    public CustomerFnoUniverseCoverageController(FnoUniverseCoverageService coverage) {
        this.coverage = coverage;
    }

    @GetMapping("/coverage")
    public ResponseEntity<FnoUniverseCoverageService.Evidence> coverage(
            @RequestParam("instrumentKey") String instrumentKey,
            Authentication authentication) {
        if (!(authentication instanceof JwtAuthenticationToken token) ||
                token.getAuthorities().stream().noneMatch(
                    a -> "ROLE_CUSTOMER".equals(a.getAuthority()))) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED,
                "Customer authorization required");
        }
        if (instrumentKey == null || !instrumentKey.matches(
                "NSE_FO\\|[A-Za-z0-9._-]{1,64}")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "Invalid F&O contract key");
        }
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
            .body(coverage.inspect(instrumentKey));
    }
}
