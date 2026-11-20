package in.aitradingsystem.tradingrooms;

import in.aitradingsystem.user.CustomerIdentityStore;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.core.env.Environment;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * Always available, customer-owned F&O PAPER capability preflight.
 *
 * <p>Other F&O controllers are conditionally absent when the PAPER/V3 flags
 * are disabled. This read-only endpoint must NOT share that condition.
 * It does not read broker credentials, quotes, funds or place an order.
 */
@RestController
@RequestMapping("/api/customer/trading-rooms/{roomId}/paper/fno")
public final class CustomerPaperFnoCapabilityController {
    private final JdbcClient jdbc;
    private final CustomerIdentityStore identities;
    private final Environment environment;

    public CustomerPaperFnoCapabilityController(JdbcClient jdbc,
            CustomerIdentityStore identities, Environment environment) {
        this.jdbc = jdbc;
        this.identities = identities;
        this.environment = environment;
    }

    public record Capability(String status, boolean readOnlyConfigured,
            boolean paperConfigured, boolean liveExecutionAuthorized,
            List<String> blockerCodes) {
        public Capability {
            blockerCodes = List.copyOf(blockerCodes);
        }
    }

    @GetMapping("/capability")
    public ResponseEntity<Capability> capability(@PathVariable UUID roomId,
            Authentication authentication) {
        UUID owner = owner(authentication);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
            .body(internalCapability(owner, roomId));
    }

    Capability internalCapability(UUID owner, UUID roomId) {
        // Ownership is checked BEFORE reporting any configuration. Do not leak
        // that another customer has a room, or admit a LIVE/equity-only room.
        String focus = jdbc.sql("""
                SELECT focus FROM customer_trading_room
                WHERE id=:id AND user_id=:owner AND mode='PAPER'
                  AND market_scope IN ('FNO','ALL')
                """)
            .param("id", roomId).param("owner", owner)
            .query(String.class).optional().orElseThrow(() ->
                new ResponseStatusException(HttpStatus.NOT_FOUND, "PAPER F&O room unavailable"));

        Capability result = assess(focus,
            enabled("minttick.upstox-fno.paper-enabled"),
            enabled("minttick.upstox-fno.v3-feed-enabled"),
            enabled("minttick.upstox-fno.display-rights-verified"),
            enabled("minttick.upstox-fno.paper-usage-rights-verified"));
        return result;
    }

    private boolean enabled(String key) {
        // Default-deny, matching the conditionally registered F&O controllers.
        return environment.getProperty(key, Boolean.class, false);
    }

    /** Pure, deterministic policy used for regression tests and the HTTP view. */
    static Capability assess(String focus, boolean paper, boolean v3,
            boolean displayRights, boolean paperRights) {
        List<String> blockers = new ArrayList<>();
        boolean supportedFocus = "INTRADAY".equals(focus) || "SWING".equals(focus);
        if (!supportedFocus) blockers.add("ROOM_HORIZON_UNSUPPORTED");
        if (!paper) blockers.add("FNO_PAPER_DISABLED");
        if (!v3) blockers.add("FNO_V3_FEED_DISABLED");
        if (!displayRights) blockers.add("DISPLAY_RIGHTS_NOT_VERIFIED");
        if (!paperRights) blockers.add("PAPER_USE_RIGHTS_NOT_VERIFIED");

        // Market data is a distinct entitlement from virtual order execution.
        // A permitted read-only real exchange display must not be coupled to
        // PAPER use rights; unverified display rights still block all quotes.
        boolean readOnly = supportedFocus && v3 && displayRights;
        // This only means the local configuration permits a PAPER attempt.
        // It is NEVER evidence of a listed contract, fresh quote, broker
        // entitlement, sufficient cash, or approval to execute an order.
        boolean paperConfigured = readOnly && paper && paperRights;
        String status = blockers.isEmpty() ? "SOURCE_CHECK_REQUIRED" : blockers.getFirst();
        return new Capability(status, readOnly, paperConfigured, false, blockers);
    }

    private UUID owner(Authentication authentication) {
        if (!(authentication instanceof JwtAuthenticationToken token)
                || token.getAuthorities().stream()
                    .noneMatch(a -> "ROLE_CUSTOMER".equals(a.getAuthority()))) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED,
                "Customer session required");
        }
        return identities.find("cognito", token.getToken().getIssuer().toString(),
                token.getToken().getSubject())
            .filter(a -> "ACTIVE".equals(a.status()) && "CUSTOMER".equals(a.role()))
            .map(CustomerIdentityStore.CustomerAccount::id)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED,
                "Customer not linked"));
    }
}
