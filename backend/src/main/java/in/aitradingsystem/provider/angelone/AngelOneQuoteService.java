package in.aitradingsystem.provider.angelone;

import org.springframework.stereotype.Service;

import java.math.BigDecimal;

/**
 * Read-only last-traded-price lookups. Retries once after a provider-reported session expiry.
 * Callers must supply a verified exchange and symbol token; no token is ever guessed.
 */
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
        try {
            BigDecimal ltp = fetch(exchange, tradingSymbol, symbolToken);
            health.recordSuccess();
            return ltp;
        } catch (AngelOneException e) {
            if (e.kind() == AngelOneException.Kind.SESSION_EXPIRED) {
                session.invalidate();
                return retryOnce(exchange, tradingSymbol, symbolToken);
            }
            health.recordFailure(e);
            throw e;
        }
    }

    /** Exactly one retry after a session expiry. The retry is a separate provider request, so it is throttled too. */
    private BigDecimal retryOnce(String exchange, String tradingSymbol, String symbolToken) {
        try {
            rateLimiter.acquire(AngelOneRateLimiter.Category.MARKET_QUOTE);
            BigDecimal ltp = api.ltp(session.accessToken(), exchange, tradingSymbol, symbolToken);
            health.recordSuccess();
            return ltp;
        } catch (AngelOneException e) {
            health.recordFailure(e);
            throw e;
        }
    }

    private BigDecimal fetch(String exchange, String tradingSymbol, String symbolToken) {
        rateLimiter.acquire(AngelOneRateLimiter.Category.MARKET_QUOTE);
        String jwt = session.accessToken();
        return api.ltp(jwt, exchange, tradingSymbol, symbolToken);
    }
}
