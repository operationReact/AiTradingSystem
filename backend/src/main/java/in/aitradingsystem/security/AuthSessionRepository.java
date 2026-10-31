package in.aitradingsystem.security;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class AuthSessionRepository {
    private final JdbcClient jdbc;

    public AuthSessionRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public AuthSession insert(UUID id, UUID userId, String tokenHash, OffsetDateTime expiresAt, String actor) {
        int inserted = jdbc.sql("""
                INSERT INTO auth_session
                    (id, user_id, token_hash, expires_at, created_by, updated_by)
                SELECT :id, u.id, :tokenHash, :expiresAt, :actor, :actor
                FROM app_user u
                WHERE u.id = :userId AND u.status = 'ACTIVE'
                """)
            .param("id", id)
            .param("userId", userId)
            .param("tokenHash", tokenHash)
            .param("expiresAt", expiresAt)
            .param("actor", actor)
            .update();
        if (inserted != 1) {
            throw new IllegalArgumentException("Cannot create a session for an unknown or disabled user");
        }
        return findById(id).orElseThrow();
    }

    public Optional<AuthSession> findById(UUID id) {
        return jdbc.sql(BASE_SELECT + " WHERE s.id = :id")
            .param("id", id)
            .query(AuthSessionRepository::map)
            .optional();
    }

    /** Resolves only non-revoked, unexpired sessions belonging to an active user. */
    public Optional<AuthSession> findActiveByTokenHash(String tokenHash, OffsetDateTime now) {
        return jdbc.sql(BASE_SELECT + """
                 WHERE s.token_hash = :tokenHash
                   AND s.revoked_at IS NULL
                   AND s.expires_at > :now
                   AND u.status = 'ACTIVE'
                """)
            .param("tokenHash", tokenHash)
            .param("now", now)
            .query(AuthSessionRepository::map)
            .optional();
    }

    public boolean revokeByTokenHash(String tokenHash, OffsetDateTime now, String actor) {
        return jdbc.sql("""
                UPDATE auth_session
                   SET revoked_at = :now,
                       updated_at = :now,
                       updated_by = :actor
                 WHERE token_hash = :tokenHash
                   AND revoked_at IS NULL
                """)
            .param("tokenHash", tokenHash)
            .param("now", now)
            .param("actor", actor)
            .update() == 1;
    }

    public boolean touch(UUID sessionId, OffsetDateTime now, String actor) {
        return jdbc.sql("""
                UPDATE auth_session
                   SET last_seen_at = :now,
                       updated_at = :now,
                       updated_by = :actor
                 WHERE id = :id
                   AND revoked_at IS NULL
                   AND expires_at > :now
                """)
            .param("id", sessionId)
            .param("now", now)
            .param("actor", actor)
            .update() == 1;
    }

    private static final String BASE_SELECT = """
            SELECT s.id, s.user_id, u.external_subject, u.display_name, u.email,
                   s.created_at, s.expires_at, s.last_seen_at, s.revoked_at
              FROM auth_session s
              JOIN app_user u ON u.id = s.user_id
            """;

    private static AuthSession map(ResultSet rs, int row) throws SQLException {
        return new AuthSession(
            rs.getObject("id", UUID.class),
            rs.getObject("user_id", UUID.class),
            rs.getString("external_subject"),
            rs.getString("display_name"),
            rs.getString("email"),
            rs.getObject("created_at", OffsetDateTime.class),
            rs.getObject("expires_at", OffsetDateTime.class),
            rs.getObject("last_seen_at", OffsetDateTime.class),
            rs.getObject("revoked_at", OffsetDateTime.class));
    }
}
