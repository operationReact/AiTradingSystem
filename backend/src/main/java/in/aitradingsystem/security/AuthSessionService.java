package in.aitradingsystem.security;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * Owns opaque server sessions. The raw token is returned exactly once when a session is issued;
 * only its SHA-256 digest is persisted. Callers must transport the raw token through a secure
 * platform mechanism (HttpOnly Secure cookie on web, platform-secure storage on native clients).
 */
@Service
public class AuthSessionService {
    private static final int TOKEN_BYTES = 32;
    private static final Duration MAX_TTL = Duration.ofDays(30);
    private static final SecureRandom RANDOM = new SecureRandom();

    private final AuthSessionRepository repository;

    public AuthSessionService(AuthSessionRepository repository) {
        this.repository = repository;
    }

    public IssuedSession issue(UUID userId, Duration ttl, String actor) {
        requireTtl(ttl);
        requireActor(actor);
        String rawToken = newToken();
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        AuthSession session = repository.insert(
            UUID.randomUUID(), userId, hashToken(rawToken), now.plus(ttl), actor);
        return new IssuedSession(rawToken, session);
    }

    public Optional<AuthSession> resolve(String rawToken) {
        if (rawToken == null || rawToken.isBlank()) {
            return Optional.empty();
        }
        return repository.findActiveByTokenHash(hashToken(rawToken), OffsetDateTime.now(ZoneOffset.UTC));
    }

    public boolean revoke(String rawToken, String actor) {
        if (rawToken == null || rawToken.isBlank()) {
            return false;
        }
        requireActor(actor);
        return repository.revokeByTokenHash(
            hashToken(rawToken), OffsetDateTime.now(ZoneOffset.UTC), actor);
    }

    public boolean touch(AuthSession session, String actor) {
        requireActor(actor);
        return repository.touch(session.id(), OffsetDateTime.now(ZoneOffset.UTC), actor);
    }

    static String hashToken(String rawToken) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                .digest(rawToken.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
    }

    private static String newToken() {
        byte[] bytes = new byte[TOKEN_BYTES];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static void requireTtl(Duration ttl) {
        if (ttl == null || ttl.isZero() || ttl.isNegative() || ttl.compareTo(MAX_TTL) > 0) {
            throw new IllegalArgumentException("Session TTL must be greater than zero and at most 30 days");
        }
    }

    private static void requireActor(String actor) {
        if (actor == null || actor.isBlank() || actor.length() > 120) {
            throw new IllegalArgumentException("actor must be non-empty and at most 120 characters");
        }
    }

    public record IssuedSession(String token, AuthSession session) { }
}
