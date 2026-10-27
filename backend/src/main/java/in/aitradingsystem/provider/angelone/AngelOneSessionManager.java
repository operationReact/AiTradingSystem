package in.aitradingsystem.provider.angelone;

import in.aitradingsystem.config.AngelOneProperties;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Instant;

/**
 * Owns the SmartAPI session tokens in memory. Tokens are never persisted, logged, or returned to the frontend.
 * A session is reused until it is invalidated by the provider or exceeds the configured maximum age.
 */
@Component
public class AngelOneSessionManager {

    private final AngelOneProperties properties;
    private final AngelOneApiClient api;
    private final AngelOneRateLimiter rateLimiter;
    private final Clock clock;

    private String jwtToken;
    private Instant authenticatedAt;

    @Autowired
    public AngelOneSessionManager(AngelOneProperties properties, AngelOneApiClient api, AngelOneRateLimiter rateLimiter) {
        this(properties, api, rateLimiter, Clock.systemUTC());
    }

    AngelOneSessionManager(AngelOneProperties properties, AngelOneApiClient api, AngelOneRateLimiter rateLimiter,
                           Clock clock) {
        this.properties = properties;
        this.api = api;
        this.rateLimiter = rateLimiter;
        this.clock = clock;
    }

    public boolean credentialsConfigured() {
        return properties.credentialsConfigured();
    }

    /** Returns a valid JWT, logging in only when no current session exists. */
    public synchronized String accessToken() {
        requireConfigured();
        if (jwtToken != null && authenticatedAt.plusSeconds(properties.sessionMaxAgeSeconds()).isAfter(clock.instant())) {
            return jwtToken;
        }
        return login();
    }

    /** Performs a fresh TOTP login. Throws AngelOneException on failure with no token retained. */
    public synchronized String login() {
        requireConfigured();
        rateLimiter.acquire(AngelOneRateLimiter.Category.AUTH);
        invalidate();
        String totp = AngelOneTotp.generate(properties.totpSecret(), clock.instant());
        AngelOneApiClient.LoginTokens tokens = api.login(properties.clientCode(), properties.password(), totp);
        jwtToken = tokens.jwtToken();
        authenticatedAt = clock.instant();
        return jwtToken;
    }

    /** Drops the in-memory session so the next call re-authenticates. */
    public synchronized void invalidate() {
        jwtToken = null;
        authenticatedAt = null;
    }

    public synchronized boolean hasActiveSession() {
        return jwtToken != null && authenticatedAt.plusSeconds(properties.sessionMaxAgeSeconds()).isAfter(clock.instant());
    }

    private void requireConfigured() {
        if (!properties.credentialsConfigured()) {
            throw new AngelOneException(AngelOneException.Kind.NOT_CONFIGURED, "NOT_CONFIGURED",
                    "Angel One credentials are not configured");
        }
    }
}
