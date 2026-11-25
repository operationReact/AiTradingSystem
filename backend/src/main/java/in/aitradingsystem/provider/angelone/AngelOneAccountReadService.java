package in.aitradingsystem.provider.angelone;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.stereotype.Service;

/**
 * Read-only account access for Angel One SmartAPI. This service cannot place, modify or cancel orders.
 * It reuses the in-memory provider session, bounded safe-read retries, provider-specific pacing and one
 * explicit session-recovery attempt.
 */
@Service
public class AngelOneAccountReadService {
    private final AngelOneSessionManager session;
    private final AngelOneApiClient api;
    private final AngelOneRateLimiter rateLimiter;
    private final AngelOneHealthService health;
    private final AngelOneRetryPolicy retryPolicy;

    public AngelOneAccountReadService(AngelOneSessionManager session, AngelOneApiClient api,
                                      AngelOneRateLimiter rateLimiter, AngelOneHealthService health,
                                      AngelOneRetryPolicy retryPolicy) {
        this.session = session;
        this.api = api;
        this.rateLimiter = rateLimiter;
        this.health = health;
        this.retryPolicy = retryPolicy;
    }

    public JsonNode funds() {
        return read(AngelOneApiClient.RMS_PATH, AngelOneRateLimiter.Category.ACCOUNT_FUNDS, api::rms);
    }

    public JsonNode holdings() {
        return read(AngelOneApiClient.HOLDINGS_PATH, AngelOneRateLimiter.Category.ACCOUNT_PORTFOLIO, api::holdings);
    }

    public JsonNode allHoldings() {
        return read(AngelOneApiClient.ALL_HOLDINGS_PATH, AngelOneRateLimiter.Category.ACCOUNT_PORTFOLIO,
                api::allHoldings);
    }

    public JsonNode positions() {
        return read(AngelOneApiClient.POSITIONS_PATH, AngelOneRateLimiter.Category.ACCOUNT_PORTFOLIO, api::positions);
    }

    public JsonNode orderBook() {
        return read(AngelOneApiClient.ORDER_BOOK_PATH, AngelOneRateLimiter.Category.ACCOUNT_PORTFOLIO,
                api::orderBook);
    }

    private JsonNode read(String endpoint, AngelOneRateLimiter.Category category, TokenRead reader) {
        String usedToken = null;
        try {
            usedToken = session.accessToken();
            JsonNode result = readWithPolicy(endpoint, category, usedToken, reader);
            health.recordSuccess();
            return result;
        } catch (AngelOneException e) {
            if (e.kind() == AngelOneException.Kind.SESSION_EXPIRED && usedToken != null) {
                return retryOnce(endpoint, category, usedToken, reader);
            }
            health.recordFailure(e);
            throw e;
        }
    }

    private JsonNode retryOnce(String endpoint, AngelOneRateLimiter.Category category, String failedToken,
                               TokenRead reader) {
        try {
            // Reserve provider capacity before re-authentication. This avoids creating a fresh session when the
            // protected read cannot be retried within the configured bounded recovery wait.
            rateLimiter.acquireForRecovery(category);
            String retryToken = session.recoverAfterSessionExpiry(failedToken);
            JsonNode result = retryPolicy.executeSafeRead(endpoint,
                    () -> reader.read(retryToken),
                    () -> {
                        rateLimiter.acquireForRecovery(category);
                        return reader.read(retryToken);
                    });
            health.recordSuccess();
            return result;
        } catch (AngelOneException e) {
            health.recordFailure(e);
            throw e;
        }
    }

    private JsonNode readWithPolicy(String endpoint, AngelOneRateLimiter.Category category, String token,
                                    TokenRead reader) {
        return retryPolicy.executeSafeRead(endpoint,
                () -> {
                    rateLimiter.acquire(category);
                    return reader.read(token);
                },
                () -> {
                    rateLimiter.acquireForRecovery(category);
                    return reader.read(token);
                });
    }

    @FunctionalInterface
    private interface TokenRead {
        JsonNode read(String jwtToken);
    }
}
