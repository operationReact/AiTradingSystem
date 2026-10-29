package in.aitradingsystem.provider.angelone;

import java.math.BigDecimal;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/** Read-only LTP lookups with bounded transient retries and one explicit session-refresh retry. */
@Service
public class AngelOneQuoteService {
    private final AngelOneSessionManager session;
    private final AngelOneApiClient api;
    private final AngelOneRateLimiter rateLimiter;
    private final AngelOneHealthService health;
    private final AngelOneRetryPolicy retryPolicy;

    @Autowired
    public AngelOneQuoteService(AngelOneSessionManager session, AngelOneApiClient api,
                                AngelOneRateLimiter rateLimiter, AngelOneHealthService health,
                                AngelOneRetryPolicy retryPolicy) {
        this.session = session;
        this.api = api;
        this.rateLimiter = rateLimiter;
        this.health = health;
        this.retryPolicy = retryPolicy;
    }

    /** Backwards-compatible fixture constructor; focused legacy tests keep their exact one-attempt behavior. */
    AngelOneQuoteService(AngelOneSessionManager session, AngelOneApiClient api,
                         AngelOneRateLimiter rateLimiter, AngelOneHealthService health) {
        this.session = session;
        this.api = api;
        this.rateLimiter = rateLimiter;
        this.health = health;
        this.retryPolicy = null;
    }

    public BigDecimal ltp(String exchange, String tradingSymbol, String symbolToken) {
        String usedToken = null;
        try {
            usedToken = session.accessToken();
            BigDecimal price = readWithPolicy(usedToken, exchange, tradingSymbol, symbolToken);
            health.recordQuoteSuccess();
            return price;
        } catch (AngelOneException e) {
            if (e.kind() == AngelOneException.Kind.SESSION_EXPIRED && usedToken != null) {
                return retryOnce(exchange, tradingSymbol, symbolToken, usedToken);
            }
            health.recordFailure(e);
            throw e;
        }
    }

    private BigDecimal retryOnce(String exchange, String tradingSymbol, String symbolToken, String failedToken) {
        try {
            // Reserve the retry quote slot before re-authentication. If quote capacity is unavailable, fail without
            // creating needless login traffic or invalidating/replacing the current provider session.
            rateLimiter.acquireForRecovery(AngelOneRateLimiter.Category.MARKET_QUOTE);
            String retryToken = session.recoverAfterSessionExpiry(failedToken);
            BigDecimal price;
            if (retryPolicy == null) {
                price = api.ltp(retryToken, exchange, tradingSymbol, symbolToken);
            } else {
                price = retryPolicy.executeSafeRead(AngelOneApiClient.LTP_PATH,
                        () -> api.ltp(retryToken, exchange, tradingSymbol, symbolToken),
                        () -> {
                            rateLimiter.acquireForRecovery(AngelOneRateLimiter.Category.MARKET_QUOTE);
                            return api.ltp(retryToken, exchange, tradingSymbol, symbolToken);
                        });
            }
            health.recordQuoteSuccess();
            return price;
        } catch (AngelOneException e) {
            health.recordFailure(e);
            throw e;
        }
    }

    private BigDecimal readWithPolicy(String token, String exchange, String tradingSymbol, String symbolToken) {
        if (retryPolicy == null) {
            rateLimiter.acquire(AngelOneRateLimiter.Category.MARKET_QUOTE);
            return api.ltp(token, exchange, tradingSymbol, symbolToken);
        }
        return retryPolicy.executeSafeRead(AngelOneApiClient.LTP_PATH,
                () -> {
                    rateLimiter.acquire(AngelOneRateLimiter.Category.MARKET_QUOTE);
                    return api.ltp(token, exchange, tradingSymbol, symbolToken);
                },
                () -> {
                    rateLimiter.acquireForRecovery(AngelOneRateLimiter.Category.MARKET_QUOTE);
                    return api.ltp(token, exchange, tradingSymbol, symbolToken);
                });
    }
}
