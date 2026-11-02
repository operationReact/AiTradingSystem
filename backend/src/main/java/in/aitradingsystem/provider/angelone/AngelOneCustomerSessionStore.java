package in.aitradingsystem.provider.angelone;

import in.aitradingsystem.config.AngelOneProperties;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Ephemeral, app-session-scoped customer broker connections.
 *
 * <p>PIN and TOTP values never enter this store. API keys and provider JWTs are kept only in process memory,
 * are bounded by both the provider-session maximum age and the app-session expiry, and disappear on process restart.
 * Nothing in this class persists broker credentials or tokens to PostgreSQL.</p>
 */
@Component
public class AngelOneCustomerSessionStore {
    private final AngelOneProperties properties;
    private final Clock clock;
    private final ConcurrentMap<UUID, Connection> connections = new ConcurrentHashMap<>();

    @Autowired
    public AngelOneCustomerSessionStore(AngelOneProperties properties) {
        this(properties, Clock.systemUTC());
    }

    AngelOneCustomerSessionStore(AngelOneProperties properties, Clock clock) {
        this.properties = properties;
        this.clock = clock;
    }

    public Connection bind(UUID appSessionId, String apiKey, String clientCode, String jwtToken,
                           OffsetDateTime appSessionExpiresAt) {
        if (appSessionId == null) throw new IllegalArgumentException("appSessionId is required");
        String boundedApiKey = requireBounded(apiKey, 256, "apiKey");
        String boundedClientCode = requireBounded(clientCode, 64, "clientCode");
        String boundedJwt = requireBounded(jwtToken, 8192, "jwtToken");
        if (appSessionExpiresAt == null) throw new IllegalArgumentException("appSessionExpiresAt is required");

        Instant now = clock.instant();
        Instant appExpiry = appSessionExpiresAt.toInstant();
        if (!appExpiry.isAfter(now)) {
            throw new IllegalArgumentException("app session must still be active");
        }
        Instant providerExpiry = now.plusSeconds(properties.sessionMaxAgeSeconds());
        Instant expiresAt = providerExpiry.isBefore(appExpiry) ? providerExpiry : appExpiry;
        Connection connection = new Connection(boundedApiKey, boundedClientCode, boundedJwt, now, expiresAt);
        connections.put(appSessionId, connection);
        return connection;
    }

    public Optional<Connection> findActive(UUID appSessionId) {
        if (appSessionId == null) return Optional.empty();
        Connection connection = connections.get(appSessionId);
        if (connection == null) return Optional.empty();
        if (!connection.expiresAt().isAfter(clock.instant())) {
            connections.remove(appSessionId, connection);
            return Optional.empty();
        }
        return Optional.of(connection);
    }

    public Connection requireActive(UUID appSessionId) {
        return findActive(appSessionId).orElseThrow(() -> new AngelOneException(
                AngelOneException.Kind.SESSION_EXPIRED,
                "CUSTOMER_BROKER_SESSION_MISSING",
                "Angel One connection is not active"));
    }

    public boolean revoke(UUID appSessionId) {
        return appSessionId != null && connections.remove(appSessionId) != null;
    }

    int activeConnectionCount() {
        connections.entrySet().removeIf(entry -> !entry.getValue().expiresAt().isAfter(clock.instant()));
        return connections.size();
    }

    private static String requireBounded(String value, int maxLength, String field) {
        if (value == null || value.isBlank() || value.length() > maxLength) {
            throw new IllegalArgumentException(field + " must be non-empty and at most " + maxLength + " characters");
        }
        return value;
    }

    public record Connection(
            String apiKey,
            String clientCode,
            String jwtToken,
            Instant authenticatedAt,
            Instant expiresAt) {
        @Override
        public String toString() {
            return "Connection[clientCode=" + clientCode + ", credentials=redacted, authenticatedAt="
                    + authenticatedAt + ", expiresAt=" + expiresAt + "]";
        }
    }
}
