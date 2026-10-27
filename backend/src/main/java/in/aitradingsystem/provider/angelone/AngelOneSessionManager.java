package in.aitradingsystem.provider.angelone;

import in.aitradingsystem.config.AngelOneProperties;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/** Keeps the active SmartAPI JWT in process memory only; never persists or exposes it to clients. */
@Component
public class AngelOneSessionManager {
    private final AngelOneProperties properties;
    private final AngelOneApiClient api;
    private final AngelOneRateLimiter rateLimiter;
    private final Clock clock;
    private String jwtToken;
    private Instant authenticatedAt;

    @Autowired
    public AngelOneSessionManager(AngelOneProperties properties, AngelOneApiClient api,
                                  AngelOneRateLimiter rateLimiter) {
        this(properties, api, rateLimiter, Clock.systemUTC());
    }

    AngelOneSessionManager(AngelOneProperties properties, AngelOneApiClient api,
                           AngelOneRateLimiter rateLimiter, Clock clock) {
        this.properties = properties;
        this.api = api;
        this.rateLimiter = rateLimiter;
        this.clock = clock;
    }

    public boolean credentialsConfigured() {
        return properties.credentialsConfigured();
    }

    public synchronized String accessToken() {
        requireConfigured();
        if (hasActiveSession()) {
            return jwtToken;
        }
        invalidate();
        return login();
    }

    /** Manual/connection-test login fails fast when the configured auth throttle is active. */
    public synchronized String login() {
        requireConfigured();
        rateLimiter.acquire(AngelOneRateLimiter.Category.AUTH);
        return authenticate();
    }

    /**
     * Refreshes only when the failed JWT is still current. A delayed expiry from an old request must not invalidate
     * a newer session established by another request.
     */
    public synchronized String recoverAfterSessionExpiry(String failedJwtToken) {
        requireConfigured();
        if (hasActiveSession() && !Objects.equals(jwtToken, failedJwtToken)) {
            return jwtToken;
        }
        invalidate();
        rateLimiter.acquireForRecovery(AngelOneRateLimiter.Category.AUTH);
        return authenticate();
    }

    private String authenticate() {
        invalidate();
        final String totp;
        try {
            totp = AngelOneTotp.generate(properties.totpSecret(), clock.instant());
        } catch (IllegalArgumentException e) {
            throw new AngelOneException(AngelOneException.Kind.NOT_CONFIGURED, "INVALID_TOTP_SECRET",
                    "Angel One TOTP secret is invalid");
        }
        try {
            AngelOneApiClient.LoginTokens tokens = api.login(properties.clientCode(), properties.password(), totp);
            jwtToken = tokens.jwtToken();
            authenticatedAt = clock.instant();
            return jwtToken;
        } catch (RuntimeException e) {
            invalidate();
            throw e;
        }
    }

    public synchronized void invalidate() {
        jwtToken = null;
        authenticatedAt = null;
    }

    public synchronized boolean hasActiveSession() {
        return jwtToken != null && authenticatedAt != null
                && authenticatedAt.plusSeconds(properties.sessionMaxAgeSeconds()).isAfter(clock.instant());
    }

    public synchronized Long sessionAgeSeconds() {
        if (jwtToken == null || authenticatedAt == null) {
            return null;
        }
        return Math.max(0L, Duration.between(authenticatedAt, clock.instant()).getSeconds());
    }

    private void requireConfigured() {
        if (!properties.credentialsConfigured()) {
            throw new AngelOneException(AngelOneException.Kind.NOT_CONFIGURED, "NOT_CONFIGURED",
                    "Angel One credentials are not configured");
        }
    }
}
