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
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class UserSessionStore {
    private static final SecureRandom RANDOM = new SecureRandom();

    private final JdbcClient jdbc;

    public UserSessionStore(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional
    public IssuedSession create(UUID userId, Duration ttl, String userAgent, String ipAddress) {
        if (ttl == null || ttl.isZero() || ttl.isNegative() || ttl.compareTo(Duration.ofDays(7)) > 0) {
            throw new IllegalArgumentException("session ttl must be between 1 second and 7 days");
        }
        String rawToken = randomToken();
        String csrfToken = randomToken();
        UUID id = UUID.randomUUID();
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        OffsetDateTime expiresAt = now.plus(ttl);
        jdbc.sql("""
                INSERT INTO user_session
                    (id, user_id, token_hash, csrf_token_hash, created_at, expires_at, last_seen_at,
                     user_agent_hash, ip_hash)
                VALUES (:id, :userId, :tokenHash, :csrfHash, :now, :expiresAt, :now, :userAgentHash, :ipHash)
                """)
            .param("id", id)
            .param("userId", userId)
            .param("tokenHash", sha256(rawToken))
            .param("csrfHash", sha256(csrfToken))
            .param("now", now)
            .param("expiresAt", expiresAt)
            .param("userAgentHash", nullableHash(userAgent))
            .param("ipHash", nullableHash(ipAddress))
            .update();
        return new IssuedSession(id, userId, rawToken, csrfToken, now, expiresAt);
    }

    public Optional<SessionRecord> findActiveByToken(String rawToken) {
        if (rawToken == null || rawToken.isBlank()) {
            return Optional.empty();
        }
        return jdbc.sql("""
                SELECT s.id, s.user_id, s.csrf_token_hash, s.created_at, s.expires_at, s.last_seen_at,
                       u.external_subject, u.display_name, u.email, u.status
                FROM user_session s
                JOIN app_user u ON u.id = s.user_id
                WHERE s.token_hash = :tokenHash
                  AND s.revoked_at IS NULL
                  AND s.expires_at > now()
                  AND u.status = 'ACTIVE'
                """)
            .param("tokenHash", sha256(rawToken))
            .query((rs, rowNum) -> new SessionRecord(
                rs.getObject("id", UUID.class),
                rs.getObject("user_id", UUID.class),
                rs.getString("external_subject"),
                rs.getString("display_name"),
                rs.getString("email"),
                rs.getString("csrf_token_hash"),
                rs.getObject("created_at", OffsetDateTime.class),
                rs.getObject("expires_at", OffsetDateTime.class),
                rs.getObject("last_seen_at", OffsetDateTime.class)))
            .optional();
    }

    public void touch(UUID sessionId) {
        jdbc.sql("UPDATE user_session SET last_seen_at = now() WHERE id = :id AND revoked_at IS NULL")
            .param("id", sessionId)
            .update();
    }

    public boolean revoke(UUID sessionId) {
        return jdbc.sql("""
                UPDATE user_session
                SET revoked_at = COALESCE(revoked_at, now())
                WHERE id = :id AND revoked_at IS NULL
                """)
            .param("id", sessionId)
            .update() == 1;
    }

    public int revokeAllForUser(UUID userId) {
        return jdbc.sql("""
                UPDATE user_session
                SET revoked_at = COALESCE(revoked_at, now())
                WHERE user_id = :userId AND revoked_at IS NULL
                """)
            .param("userId", userId)
            .update();
    }

    public boolean csrfMatches(SessionRecord session, String presented) {
        if (session == null || presented == null || presented.isBlank()) {
            return false;
        }
        byte[] expected = session.csrfTokenHash().getBytes(StandardCharsets.US_ASCII);
        byte[] actual = sha256(presented).getBytes(StandardCharsets.US_ASCII);
        return MessageDigest.isEqual(expected, actual);
    }

    private static String randomToken() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static String nullableHash(String value) {
        return value == null || value.isBlank() ? null : sha256(value);
    }

    static String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    public record IssuedSession(
        UUID id,
        UUID userId,
        String rawToken,
        String csrfToken,
        OffsetDateTime createdAt,
        OffsetDateTime expiresAt
    ) {}

    public record SessionRecord(
        UUID id,
        UUID userId,
        String externalSubject,
        String displayName,
        String email,
        String csrfTokenHash,
        OffsetDateTime createdAt,
        OffsetDateTime expiresAt,
        OffsetDateTime lastSeenAt
    ) {}
}
