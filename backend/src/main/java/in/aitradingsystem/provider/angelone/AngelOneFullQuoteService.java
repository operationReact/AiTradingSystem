package in.aitradingsystem.provider.angelone;

import org.springframework.stereotype.Service;

/** Read-only FULL quote lookup with the same bounded retry/session-recovery controls as other SmartAPI reads. */
@Service
public class AngelOneFullQuoteService {
    private final AngelOneSessionManager session;
    private final AngelOneMarketDataApiClient api;
    private final AngelOneRateLimiter rateLimiter;
    private final AngelOneHealthService health;
    private final AngelOneRetryPolicy retryPolicy;

    public AngelOneFullQuoteService(AngelOneSessionManager session, AngelOneMarketDataApiClient api,
                                    AngelOneRateLimiter rateLimiter, AngelOneHealthService health,
                                    AngelOneRetryPolicy retryPolicy) {
        this.session = session;
        this.api = api;
        this.rateLimiter = rateLimiter;
        this.health = health;
        this.retryPolicy = retryPolicy;
    }

    public AngelOneFullQuote fullQuote(String exchange, String symbolToken) {
        String usedToken = null;
        try {
            usedToken = session.accessToken();
            AngelOneFullQuote quote = readWithPolicy(usedToken, exchange, symbolToken);
            health.recordQuoteSuccess();
            return quote;
        } catch (AngelOneException e) {
            if (e.kind() == AngelOneException.Kind.SESSION_EXPIRED && usedToken != null) {
                return retryOnce(exchange, symbolToken, usedToken);
            }
            health.recordFailure(e);
            throw e;
        }
    }

    private AngelOneFullQuote retryOnce(String exchange, String symbolToken, String failedToken) {
        try {
            rateLimiter.acquireForRecovery(AngelOneRateLimiter.Category.MARKET_QUOTE);
            String retryToken = session.recoverAfterSessionExpiry(failedToken);
            AngelOneFullQuote quote = retryPolicy.executeSafeRead(AngelOneMarketDataApiClient.FULL_QUOTE_PATH,
                    () -> api.fullQuote(retryToken, exchange, symbolToken),
                    () -> {
                        rateLimiter.acquireForRecovery(AngelOneRateLimiter.Category.MARKET_QUOTE);
                        return api.fullQuote(retryToken, exchange, symbolToken);
                    });
            health.recordQuoteSuccess();
            return quote;
        } catch (AngelOneException e) {
            health.recordFailure(e);
            throw e;
        }
    }

    private AngelOneFullQuote readWithPolicy(String token, String exchange, String symbolToken) {
        return retryPolicy.executeSafeRead(AngelOneMarketDataApiClient.FULL_QUOTE_PATH,
                () -> {
                    rateLimiter.acquire(AngelOneRateLimiter.Category.MARKET_QUOTE);
                    return api.fullQuote(token, exchange, symbolToken);
                },
                () -> {
                    rateLimiter.acquireForRecovery(AngelOneRateLimiter.Category.MARKET_QUOTE);
                    return api.fullQuote(token, exchange, symbolToken);
                });
    }
}
