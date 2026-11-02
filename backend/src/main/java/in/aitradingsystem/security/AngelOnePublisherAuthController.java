package in.aitradingsystem.security;

import com.fasterxml.jackson.databind.JsonNode;
import in.aitradingsystem.config.AngelOneProperties;
import in.aitradingsystem.provider.angelone.AngelOneApiClient;
import in.aitradingsystem.provider.angelone.AngelOneCustomerSessionStore;
import in.aitradingsystem.provider.angelone.AngelOneException;
import in.aitradingsystem.user.UserAccountStore;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.util.UriComponentsBuilder;

/**
 * Delegated Angel One publisher login. Customer PIN/TOTP stay on Angel One's own login surface.
 * The provider authorization token is consumed server-side and is never returned to the mobile app.
 */
@RestController
@RequestMapping("/api/auth/angel-one")
public class AngelOnePublisherAuthController {
    static final String STATE_COOKIE = "ATS_ANGEL_STATE";
    static final String PUBLISHER_LOGIN_URL = "https://smartapi.angelone.in/publisher-login";

    private final AngelOneProperties properties;
    private final AngelOneApiClient api;
    private final AngelOnePublisherAuthStore publisherAuth;
    private final UserAccountStore users;
    private final UserSessionStore sessions;
    private final AngelOneCustomerSessionStore brokerSessions;
    private final Duration sessionTtl;
    private final boolean cookieSecure;
    private final String callbackUrl;
    private final String successRedirectUri;

    public AngelOnePublisherAuthController(
            AngelOneProperties properties,
            AngelOneApiClient api,
            AngelOnePublisherAuthStore publisherAuth,
            UserAccountStore users,
            UserSessionStore sessions,
            AngelOneCustomerSessionStore brokerSessions,
            @Value("${app.security.session.ttl-seconds:28800}") long ttlSeconds,
            @Value("${app.security.session.cookie-secure:true}") boolean cookieSecure,
            @Value("${app.security.angel-one.publisher.callback-url:}") String callbackUrl,
            @Value("${app.security.angel-one.publisher.success-redirect-uri:}") String successRedirectUri) {
        if (ttlSeconds < 60 || ttlSeconds > Duration.ofDays(7).toSeconds()) {
            throw new IllegalArgumentException("app.security.session.ttl-seconds must be between 60 and 604800");
        }
        this.properties = properties;
        this.api = api;
        this.publisherAuth = publisherAuth;
        this.users = users;
        this.sessions = sessions;
        this.brokerSessions = brokerSessions;
        this.sessionTtl = Duration.ofSeconds(ttlSeconds);
        this.cookieSecure = cookieSecure;
        this.callbackUrl = callbackUrl == null ? "" : callbackUrl.trim();
        this.successRedirectUri = successRedirectUri == null ? "" : successRedirectUri.trim();
    }

    /**
     * Open this endpoint in a system browser/custom tab. It establishes browser-bound state and redirects to Angel One.
     */
    @GetMapping("/start")
    public ResponseEntity<Void> start(@RequestParam String codeChallenge) {
        requirePublisherConfiguration();
        AngelOnePublisherAuthStore.AuthorizationStart start;
        try {
            start = publisherAuth.begin(codeChallenge);
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid login challenge");
        }

        URI publisherUri = UriComponentsBuilder.fromUriString(PUBLISHER_LOGIN_URL)
                .queryParam("api_key", properties.apiKey())
                .queryParam("redirect_url", callbackUrl)
                .queryParam("state", start.state())
                .build()
                .encode()
                .toUri();
        return ResponseEntity.status(HttpStatus.FOUND)
                .location(publisherUri)
                .cacheControl(CacheControl.noStore())
                .header(HttpHeaders.SET_COOKIE, stateCookie(start.state(), Duration.ofMinutes(5)).toString())
                .build();
    }

    /**
     * Angel One redirects here after its own login UI succeeds. The broker token is immediately verified via profile
     * and converted into a short-lived, PKCE-bound one-time exchange code.
     */
    @GetMapping("/callback")
    public ResponseEntity<?> callback(
            @RequestParam("auth_token") String authToken,
            @RequestParam String state,
            HttpServletRequest request) {
        requirePublisherConfiguration();
        if (authToken == null || authToken.isBlank() || authToken.length() > 8192) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid broker callback");
        }
        String cookieState = SessionAuthenticationFilter.cookieValue(request, STATE_COOKIE);
        if (!constantTimeEquals(state, cookieState)) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid broker callback state");
        }

        AngelOnePublisherAuthStore.PendingAuthorization authorization;
        try {
            authorization = publisherAuth.consumeAuthorization(state);
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Broker callback state expired");
        }

        try {
            JsonNode profile = api.profile(properties.apiKey(), authToken);
            String clientCode = text(profile, "clientcode");
            if (clientCode == null) {
                throw new AngelOneException(AngelOneException.Kind.INVALID_RESPONSE, "PROFILE_CLIENT_CODE_MISSING",
                        "Provider profile is missing client code");
            }
            clientCode = clientCode.trim().toUpperCase(Locale.ROOT);
            AngelOnePublisherAuthStore.ExchangeCode exchange = publisherAuth.createExchange(
                    authorization, clientCode, authToken, text(profile, "name"), text(profile, "email"));

            ResponseCookie clearedState = stateCookie("", Duration.ZERO);
            if (successRedirectUri.isBlank()) {
                return ResponseEntity.ok()
                        .cacheControl(CacheControl.noStore())
                        .header(HttpHeaders.SET_COOKIE, clearedState.toString())
                        .body(Map.of("exchangeCode", exchange.code(), "expiresAt", exchange.expiresAt().toString()));
            }
            URI redirect = UriComponentsBuilder.fromUriString(successRedirectUri)
                    .queryParam("code", exchange.code())
                    .build()
                    .encode()
                    .toUri();
            return ResponseEntity.status(HttpStatus.FOUND)
                    .location(redirect)
                    .cacheControl(CacheControl.noStore())
                    .header(HttpHeaders.SET_COOKIE, clearedState.toString())
                    .build();
        } catch (AngelOneException e) {
            throw providerFailure(e);
        }
    }

    /** Mobile app exchanges the one-time code using its original verifier and receives the normal app session. */
    @PostMapping("/exchange")
    public ResponseEntity<LoginView> exchange(
            @Valid @RequestBody ExchangeRequest body,
            HttpServletRequest request) {
        AngelOnePublisherAuthStore.PendingExchange pending;
        try {
            pending = publisherAuth.consumeExchange(body.code(), body.codeVerifier());
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Login exchange is invalid or expired");
        }

        String externalSubject = "angel-one:" + pending.clientCode();
        String displayName = pending.displayName() == null ? pending.clientCode() : pending.displayName();
        var user = users.findUserByExternalSubject(externalSubject)
                .orElseGet(() -> users.createUser(
                        externalSubject, displayName, pending.email(), "broker:angel-one-publisher"));
        UserSessionStore.IssuedSession issued = sessions.create(
                user.id(), sessionTtl, request.getHeader("User-Agent"), request.getRemoteAddr());
        try {
            brokerSessions.bind(issued.id(), pending.clientCode(), pending.authToken(), issued.expiresAt());
        } catch (RuntimeException e) {
            sessions.revoke(issued.id());
            throw e;
        }

        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .header(HttpHeaders.SET_COOKIE, sessionCookie(issued.rawToken(), sessionTtl).toString())
                .body(new LoginView(
                        user.id(), user.displayName(), user.email(), pending.clientCode(), issued.expiresAt(),
                        issued.csrfToken(), true, "ANGEL_ONE_PUBLISHER"));
    }

    private void requirePublisherConfiguration() {
        if (properties.apiKey() == null || properties.apiKey().isBlank() || callbackUrl.isBlank()) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Angel One publisher login is not configured");
        }
        URI callback = URI.create(callbackUrl);
        boolean localhostHttp = "http".equalsIgnoreCase(callback.getScheme())
                && ("localhost".equalsIgnoreCase(callback.getHost()) || "127.0.0.1".equals(callback.getHost()));
        if (!("https".equalsIgnoreCase(callback.getScheme()) || localhostHttp) || callback.getHost() == null) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Angel One callback URL is invalid");
        }
        if (!successRedirectUri.isBlank() && URI.create(successRedirectUri).getScheme() == null) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Angel One success redirect URI is invalid");
        }
    }

    private ResponseStatusException providerFailure(AngelOneException failure) {
        HttpStatus status = switch (failure.kind()) {
            case SESSION_EXPIRED, REJECTED -> HttpStatus.UNAUTHORIZED;
            case RATE_LIMITED -> HttpStatus.TOO_MANY_REQUESTS;
            case UNAVAILABLE, NOT_CONFIGURED -> HttpStatus.SERVICE_UNAVAILABLE;
            case INVALID_RESPONSE -> HttpStatus.BAD_GATEWAY;
        };
        return new ResponseStatusException(status, "Angel One login verification failed");
    }

    private ResponseCookie stateCookie(String value, Duration maxAge) {
        return ResponseCookie.from(STATE_COOKIE, value)
                .httpOnly(true)
                .secure(cookieSecure)
                .sameSite("Lax")
                .path("/api/auth/angel-one")
                .maxAge(maxAge)
                .build();
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

    private static boolean constantTimeEquals(String left, String right) {
        if (left == null || right == null) return false;
        return MessageDigest.isEqual(
                left.getBytes(StandardCharsets.US_ASCII), right.getBytes(StandardCharsets.US_ASCII));
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null || value.isNull() || value.asText().isBlank() ? null : value.asText().trim();
    }

    public record ExchangeRequest(
            @NotBlank @Size(max = 256) String code,
            @NotBlank @Size(min = 43, max = 128) String codeVerifier) {
        @Override
        public String toString() {
            return "ExchangeRequest[redacted]";
        }
    }

    public record LoginView(
            UUID userId,
            String displayName,
            String email,
            String brokerClientCode,
            OffsetDateTime expiresAt,
            String csrfToken,
            boolean brokerConnected,
            String authenticationType) {}
}
