package in.aitradingsystem.mutualfund;

import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;

/** Customer read-only view; operator alone can force public NAV refresh. */
@RestController
public class MfResearchController {
    private final MfResearchStore store;
    private final MfResearchRefreshService refresh;
    public MfResearchController(MfResearchStore store,MfResearchRefreshService refresh) {
        this.store = store; this.refresh = refresh;
    }

    @GetMapping("/api/customer/mutual-funds/v1/board")
    public ResponseEntity<MfResearchStore.Board> board(Authentication auth) {
        if (!(auth instanceof JwtAuthenticationToken token)
            || token.getAuthorities().stream().noneMatch(
                a -> "ROLE_CUSTOMER".equals(a.getAuthority()))) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED,
                "Verified customer sign-in required");
        }
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(store.board());
    }

    @PostMapping("/api/admin/mutual-funds/refresh")
    public ResponseEntity<MfResearchStore.Feed> refresh(Authentication auth) {
        // /api/admin/** is ADMIN/OPERATOR-restricted by SecurityConfig.
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
            .body(refresh.refresh());
    }
}
