package in.aitradingsystem.security;

import in.aitradingsystem.security.SessionAuthenticationFilter.SessionPrincipal;
import in.aitradingsystem.user.UserAccountStore;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/auth")
public class AuthSessionController {
    private static final String DEV_SUBJECT = "dev:api-key-operator";

    private final UserSessionStore sessions;
    private final UserAccountStore users;
    private final Duration ttl;
    private final boolean cookieSecure;
    private final boolean devBootstrapEnabled;

    public AuthSessionController(
            UserSessionStore sessions,
            UserAccountStore users,
            @Value("${app.security.session.ttl-seconds:28800}") long ttlSeconds,
            @Value("${app.security.session.cookie-secure:true}") boolean cookieSecure,
            @Value("${app.security.dev-session-bootstrap-enabled:false}") boolean devBootstrapEnabled) {
        if (ttlSeconds < 60 || ttlSeconds > Duration.ofDays(7).toSeconds()) {
            throw new IllegalArgumentException("app.security.session.ttl-seconds must be between 60 and 604800");
        }
        this.sessions = sessions;
        this.users = users;
        this.ttl = Duration.ofSeconds(ttlSeconds);
        this.cookieSecure = cookieSecure;
        this.devBootstrapEnabled = devBootstrapEnabled;
    }

    /**
     * Temporary local/dev bridge while a production IdP/login provider is selected. Disabled by default.
     * It converts a valid legacy operator API-key authentication into an HttpOnly server-side session;
     * it must never be enabled in a public production environment.
     */
    @PostMapping("/dev/session")
    public ResponseEntity<SessionView> createDevSession(Authentication authentication, HttpServletRequest request) {
        if (!devBootstrapEnabled) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        }
        if (authentication == null || authentication.getPrincipal() instanceof SessionPrincipal
                || !"operator".equals(authentication.getName())) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
        }

        var user = users.findUserByExternalSubject(DEV_SUBJECT)
            .orElseGet(() -> users.createUser(DEV_SUBJECT, "Operator", null, "system:dev-session-bootstrap"));
        UserSessionStore.IssuedSession issued = sessions.create(
            user.id(), ttl, request.getHeader("User-Agent"), request.getRemoteAddr());

        return ResponseEntity.ok()
            .header(HttpHeaders.SET_COOKIE, sessionCookie(issued.rawToken(), ttl).toString())
            .body(new SessionView(
                user.id(), user.displayName(), user.email(), issued.expiresAt(), issued.csrfToken(), "SESSION"));
    }

    @GetMapping("/session")
    public SessionView currentSession(Authentication authentication) {
        SessionContext context = requireSession(authentication);
        SessionPrincipal principal = context.principal();
        return new SessionView(
            principal.userId(), principal.displayName(), principal.email(), context.record().expiresAt(),
            null, "SESSION");
    }

    @GetMapping("/csrf")
    public Map<String, String> csrf(Authentication authentication) {
        SessionContext context = requireSession(authentication);
        // A fresh CSRF token is not derivable from the stored hash. Clients receive the token at session creation;
        // production IdP/login integration will return it in the login exchange. This endpoint intentionally
        // communicates presence only until that provider-specific exchange is implemented.
        return Map.of("status", "BOUND_TO_SESSION");
    }

    @DeleteMapping("/session")
    public ResponseEntity<Void> logout(Authentication authentication) {
        SessionContext context = requireSession(authentication);
        sessions.revoke(context.principal().sessionId());
        return ResponseEntity.noContent()
            .header(HttpHeaders.SET_COOKIE, sessionCookie("", Duration.ZERO).toString())
            .build();
    }

    private SessionContext requireSession(Authentication authentication) {
        if (authentication == null || !(authentication.getPrincipal() instanceof SessionPrincipal principal)
                || !(authentication.getDetails() instanceof UserSessionStore.SessionRecord record)) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
        }
        return new SessionContext(principal, record);
    }

    private ResponseCookie sessionCookie(String value, Duration maxAge) {
        return ResponseCookie.from(SessionAuthenticationFilter.COOKIE_NAME, value)
            .httpOnly(true)
            .secure(cookieSecure)
            .sameSite("Strict")
            .path("/")
            .maxAge(maxAge)
            .build();
    }

    public record SessionView(
        UUID userId,
        String displayName,
        String email,
        OffsetDateTime expiresAt,
        String csrfToken,
        String authenticationType
    ) {}

    private record SessionContext(SessionPrincipal principal, UserSessionStore.SessionRecord record) {}
}
