package in.aitradingsystem.security;

import java.time.OffsetDateTime;
import java.util.UUID;

/** Server-owned authentication session metadata. Raw session credentials are never stored here. */
public record AuthSession(
        UUID id,
        UUID userId,
        String externalSubject,
        String displayName,
        String email,
        OffsetDateTime createdAt,
        OffsetDateTime expiresAt,
        OffsetDateTime lastSeenAt,
        OffsetDateTime revokedAt) {

    public boolean activeAt(OffsetDateTime now) {
        return revokedAt == null && expiresAt.isAfter(now);
    }
}
