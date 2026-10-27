package in.aitradingsystem.provider.angelone;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.stereotype.Service;

/** Read-only historical-candle adapter with bounded pacing and one retry only on explicit session expiry. */
@Service
public class AngelOneHistoricalService {
    private final AngelOneSessionManager session;
    private final AngelOneApiClient api;
    private final AngelOneRateLimiter rateLimiter;

    public AngelOneHistoricalService(AngelOneSessionManager session, AngelOneApiClient api,
                                     AngelOneRateLimiter rateLimiter) {
        this.session = session;
        this.api = api;
        this.rateLimiter = rateLimiter;
    }

    public JsonNode fetch(String exchange, String symbolToken, String interval, String fromDate, String toDate) {
        String usedToken = null;
        try {
            rateLimiter.acquireForRecovery(AngelOneRateLimiter.Category.HISTORICAL_CANDLES);
            usedToken = session.accessToken();
            return api.candles(usedToken, exchange, symbolToken, interval, fromDate, toDate);
        } catch (AngelOneException ex) {
            if (ex.kind() == AngelOneException.Kind.SESSION_EXPIRED && usedToken != null) {
                return retryOnce(exchange, symbolToken, interval, fromDate, toDate, usedToken);
            }
            throw ex;
        }
    }

    private JsonNode retryOnce(String exchange, String symbolToken, String interval,
                               String fromDate, String toDate, String failedToken) {
        rateLimiter.acquireForRecovery(AngelOneRateLimiter.Category.HISTORICAL_CANDLES);
        String retryToken = session.recoverAfterSessionExpiry(failedToken);
        return api.candles(retryToken, exchange, symbolToken, interval, fromDate, toDate);
    }
}
