package in.aitradingsystem.provider.angelone;

import com.fasterxml.jackson.databind.JsonNode;
import in.aitradingsystem.config.AngelOneProperties;
import in.aitradingsystem.security.SessionAuthenticationFilter.SessionPrincipal;
import java.util.function.Function;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/** Read-only customer account views backed by the authenticated Angel One publisher session. */
@RestController
@RequestMapping("/api/broker/angel-one")
public class AngelOneCustomerAccountController {
    private final AngelOneApiClient api;
    private final AngelOneProperties properties;
    private final AngelOneCustomerSessionStore sessions;

    public AngelOneCustomerAccountController(
            AngelOneApiClient api,
            AngelOneProperties properties,
            AngelOneCustomerSessionStore sessions) {
        this.api = api;
        this.properties = properties;
        this.sessions = sessions;
    }

    @GetMapping("/profile")
    public JsonNode profile(Authentication authentication) {
        return read(authentication, connection -> api.profile(properties.apiKey(), connection.authToken()));
    }

    @GetMapping("/funds")
    public JsonNode funds(Authentication authentication) {
        return read(authentication, connection -> api.funds(properties.apiKey(), connection.authToken()));
    }

    @GetMapping("/holdings")
    public JsonNode holdings(Authentication authentication) {
        return read(authentication, connection -> api.holdings(properties.apiKey(), connection.authToken()));
    }

    @GetMapping("/positions")
    public JsonNode positions(Authentication authentication) {
        return read(authentication, connection -> api.positions(properties.apiKey(), connection.authToken()));
    }

    @GetMapping("/orders")
    public JsonNode orders(Authentication authentication) {
        return read(authentication, connection -> api.orderBook(properties.apiKey(), connection.authToken()));
    }

    @DeleteMapping("/session")
    public ResponseEntity<Void> disconnect(Authentication authentication) {
        SessionPrincipal principal = requireSession(authentication);
        sessions.revoke(principal.sessionId());
        return ResponseEntity.noContent().build();
    }

    private JsonNode read(
            Authentication authentication,
            Function<AngelOneCustomerSessionStore.Connection, JsonNode> operation) {
        SessionPrincipal principal = requireSession(authentication);
        AngelOneCustomerSessionStore.Connection connection = sessions.requireActive(principal.sessionId());
        try {
            return operation.apply(connection);
        } catch (AngelOneException e) {
            if (e.kind() == AngelOneException.Kind.SESSION_EXPIRED) {
                sessions.revoke(principal.sessionId());
            }
            throw providerFailure(e);
        }
    }

    private SessionPrincipal requireSession(Authentication authentication) {
        if (authentication == null || !(authentication.getPrincipal() instanceof SessionPrincipal principal)) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
        }
        return principal;
    }

    private ResponseStatusException providerFailure(AngelOneException failure) {
        HttpStatus status = switch (failure.kind()) {
            case SESSION_EXPIRED -> HttpStatus.UNAUTHORIZED;
            case RATE_LIMITED -> HttpStatus.TOO_MANY_REQUESTS;
            case UNAVAILABLE, NOT_CONFIGURED -> HttpStatus.SERVICE_UNAVAILABLE;
            case REJECTED, INVALID_RESPONSE -> HttpStatus.BAD_GATEWAY;
        };
        return new ResponseStatusException(status, "Angel One account data is unavailable");
    }
}
