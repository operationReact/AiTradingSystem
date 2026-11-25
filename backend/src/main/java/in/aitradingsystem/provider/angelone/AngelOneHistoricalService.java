package in.aitradingsystem.provider.angelone;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/** Read-only historical-candle adapter with verified pacing, bounded transient retries and session recovery. */
@Service
public class AngelOneHistoricalService {
    private final AngelOneSessionManager session;
    private final AngelOneApiClient api;
    private final AngelOneRateLimiter rateLimiter;
    private final AngelOneRetryPolicy retryPolicy;

    @Autowired
    public AngelOneHistoricalService(AngelOneSessionManager session, AngelOneApiClient api,
                                     AngelOneRateLimiter rateLimiter, AngelOneRetryPolicy retryPolicy) {
        this.session = session;
        this.api = api;
        this.rateLimiter = rateLimiter;
        this.retryPolicy = retryPolicy;
    }

    /** Backwards-compatible fixture constructor. */
    AngelOneHistoricalService(AngelOneSessionManager session, AngelOneApiClient api,
                              AngelOneRateLimiter rateLimiter) {
        this.session = session;
        this.api = api;
        this.rateLimiter = rateLimiter;
        this.retryPolicy = null;
    }

    public JsonNode fetch(String exchange, String symbolToken, String interval, String fromDate, String toDate) {
        String usedToken = null;
        try {
            usedToken = session.accessToken();
            return readWithPolicy(usedToken, exchange, symbolToken, interval, fromDate, toDate, false);
        } catch (AngelOneException ex) {
            if (ex.kind() == AngelOneException.Kind.SESSION_EXPIRED && usedToken != null) {
                return retryOnce(exchange, symbolToken, interval, fromDate, toDate, usedToken);
            }
            throw ex;
        }
    }

    private JsonNode retryOnce(String exchange, String symbolToken, String interval,
                               String fromDate, String toDate, String failedToken) {
        String retryToken = session.recoverAfterSessionExpiry(failedToken);
        return readWithPolicy(retryToken, exchange, symbolToken, interval, fromDate, toDate, true);
    }

    private JsonNode readWithPolicy(String token, String exchange, String symbolToken, String interval,
                                    String fromDate, String toDate, boolean recoveryFirst) {
        if (retryPolicy == null) {
            if (recoveryFirst) rateLimiter.acquireForRecovery(AngelOneRateLimiter.Category.HISTORICAL_CANDLES);
            else rateLimiter.acquireForRecovery(AngelOneRateLimiter.Category.HISTORICAL_CANDLES);
            return api.candles(token, exchange, symbolToken, interval, fromDate, toDate);
        }
        return retryPolicy.executeSafeRead(AngelOneApiClient.CANDLE_PATH,
                () -> {
                    if (recoveryFirst) rateLimiter.acquireForRecovery(AngelOneRateLimiter.Category.HISTORICAL_CANDLES);
                    else rateLimiter.acquireForRecovery(AngelOneRateLimiter.Category.HISTORICAL_CANDLES);
                    return api.candles(token, exchange, symbolToken, interval, fromDate, toDate);
                },
                () -> {
                    rateLimiter.acquireForRecovery(AngelOneRateLimiter.Category.HISTORICAL_CANDLES);
                    return api.candles(token, exchange, symbolToken, interval, fromDate, toDate);
                });
    }
}
