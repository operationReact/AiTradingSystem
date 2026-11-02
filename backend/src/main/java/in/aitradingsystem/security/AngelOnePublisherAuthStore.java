package in.aitradingsystem.security;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

/**
 * Short-lived in-memory state for the Angel One publisher-login + PKCE-style handoff.
 * Provider authorization tokens are never persisted and are never returned to the mobile client.
 */
@Component
public class AngelOnePublisherAuthStore {
    private static final Duration STATE_TTL = Duration.ofMinutes(5);
    private static final Duration EXCHANGE_TTL = Duration.ofMinutes(3);
    private static final SecureRandom RANDOM = new SecureRandom();

    private final Clock clock;
    private final Map<String, PendingAuthorization> authorizations = new ConcurrentHashMap<>();
    private final Map<String, PendingExchange> exchanges = new ConcurrentHashMap<>();

    public AngelOnePublisherAuthStore() {
        this(Clock.systemUTC());
    }

    AngelOnePublisherAuthStore(Clock clock) {
        this.clock = clock;
    }

    public AuthorizationStart begin(String codeChallenge) {
        String boundedChallenge = requireCodeChallenge(codeChallenge);
        cleanup();
        String state = randomToken();
        Instant expiresAt = clock.instant().plus(STATE_TTL);
        authorizations.put(digest(state), new PendingAuthorization(boundedChallenge, expiresAt));
        return new AuthorizationStart(state, expiresAt);
    }

    public PendingAuthorization consumeAuthorization(String state) {
        if (state == null || state.isBlank()) throw new IllegalArgumentException("state is required");
        PendingAuthorization pending = authorizations.remove(digest(state));
        if (pending == null || !pending.expiresAt().isAfter(clock.instant())) {
            throw new IllegalArgumentException("publisher authorization state is missing or expired");
        }
        return pending;
    }

    public ExchangeCode createExchange(
            PendingAuthorization authorization,
            String clientCode,
            String authToken,
            String displayName,
            String email) {
        if (authorization == null || !authorization.expiresAt().isAfter(clock.instant())) {
            throw new IllegalArgumentException("publisher authorization is expired");
        }
        String code = randomToken();
        Instant expiresAt = clock.instant().plus(EXCHANGE_TTL);
        exchanges.put(digest(code), new PendingExchange(
                requireBounded(clientCode, 64, "clientCode"),
                requireBounded(authToken, 8192, "authToken"),
                nullableBounded(displayName, 120, "displayName"),
                nullableBounded(email, 320, "email"),
                authorization.codeChallenge(),
                expiresAt));
        return new ExchangeCode(code, expiresAt);
    }

    public PendingExchange consumeExchange(String code, String codeVerifier) {
        if (code == null || code.isBlank()) throw new IllegalArgumentException("exchange code is required");
        if (codeVerifier == null || codeVerifier.length() < 43 || codeVerifier.length() > 128) {
            throw new IllegalArgumentException("code verifier length is invalid");
        }
        String key = digest(code);
        PendingExchange pending = exchanges.get(key);
        if (pending == null || !pending.expiresAt().isAfter(clock.instant())) {
            exchanges.remove(key);
            throw new IllegalArgumentException("exchange code is missing or expired");
        }
        String actualChallenge = pkceChallenge(codeVerifier);
        if (!MessageDigest.isEqual(
                pending.codeChallenge().getBytes(StandardCharsets.US_ASCII),
                actualChallenge.getBytes(StandardCharsets.US_ASCII))) {
            throw new IllegalArgumentException("code verifier does not match the login request");
        }
        if (!exchanges.remove(key, pending)) {
            throw new IllegalArgumentException("exchange code was already consumed");
        }
        return pending;
    }

    static String pkceChallenge(String verifier) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256")
                    .digest(verifier.getBytes(StandardCharsets.US_ASCII));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
    }

    private void cleanup() {
        Instant now = clock.instant();
        authorizations.entrySet().removeIf(entry -> !entry.getValue().expiresAt().isAfter(now));
        exchanges.entrySet().removeIf(entry -> !entry.getValue().expiresAt().isAfter(now));
    }

    private static String requireCodeChallenge(String value) {
        String bounded = requireBounded(value, 128, "codeChallenge");
        if (bounded.length() != 43 || !bounded.matches("[A-Za-z0-9_-]{43}")) {
            throw new IllegalArgumentException("codeChallenge must be a base64url SHA-256 challenge");
        }
        return bounded;
    }

    private static String randomToken() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static String digest(String value) {
        return pkceChallenge(value);
    }

    private static String requireBounded(String value, int maxLength, String field) {
        if (value == null || value.isBlank() || value.length() > maxLength) {
            throw new IllegalArgumentException(field + " must be non-empty and at most " + maxLength + " characters");
        }
        return value;
    }

    private static String nullableBounded(String value, int maxLength, String field) {
        if (value != null && value.length() > maxLength) {
            throw new IllegalArgumentException(field + " must be at most " + maxLength + " characters");
        }
        return value == null || value.isBlank() ? null : value;
    }

    public record AuthorizationStart(String state, Instant expiresAt) {}

    public record PendingAuthorization(String codeChallenge, Instant expiresAt) {}

    public record ExchangeCode(String code, Instant expiresAt) {}

    public record PendingExchange(
            String clientCode,
            String authToken,
            String displayName,
            String email,
            String codeChallenge,
            Instant expiresAt) {
        @Override
        public String toString() {
            return "PendingExchange[clientCode=" + clientCode + ", authToken=redacted, expiresAt=" + expiresAt + "]";
        }
    }
}
