package in.aitradingsystem.provider.angelone;

import java.math.BigDecimal;
import org.springframework.stereotype.Service;

/** Read-only LTP lookups; one recovery retry is allowed only after a provider-reported session expiry. */
@Service
public class AngelOneQuoteService {
    private final AngelOneSessionManager session;
    private final AngelOneApiClient api;
    private final AngelOneRateLimiter rateLimiter;
    private final AngelOneHealthService health;

    public AngelOneQuoteService(AngelOneSessionManager session, AngelOneApiClient api,
                                AngelOneRateLimiter rateLimiter, AngelOneHealthService health) {
        this.session = session;
        this.api = api;
        this.rateLimiter = rateLimiter;
        this.health = health;
    }

    public BigDecimal ltp(String exchange, String tradingSymbol, String symbolToken) {
        String usedToken = null;
        try {
            rateLimiter.acquire(AngelOneRateLimiter.Category.MARKET_QUOTE);
            usedToken = session.accessToken();
            BigDecimal price = api.ltp(usedToken, exchange, tradingSymbol, symbolToken);
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
            // Reserve the quote slot first, avoiding an unnecessary login if the quote throttle remains active.
            rateLimiter.acquireForRecovery(AngelOneRateLimiter.Category.MARKET_QUOTE);
            String retryToken = session.recoverAfterSessionExpiry(failedToken);
            BigDecimal price = api.ltp(retryToken, exchange, tradingSymbol, symbolToken);
            health.recordQuoteSuccess();
            return price;
        } catch (AngelOneException e) {
            health.recordFailure(e);
            throw e;
        }
    }
}
