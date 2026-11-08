package in.aitradingsystem.provider.angelone;

import in.aitradingsystem.instrument.Instrument;
import in.aitradingsystem.marketdata.MarketDataException;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * Application-owned, backend-only SmartAPI data session for MintTick's private
 * dev test. Its identity is NOT a customer or a broker trading authorization.
 * Reads the same existing SSM parameters used for the owner's optional
 * account connection. No credentials are sent to Flutter or other customers.
 *
 * The socket implementation currently supports one selected live symbol at a
 * time; on-demand quote requests use the same backend session for all users.
 * Live order execution is never enabled by this service.
 */
@Service
public final class AppAngelOneMarketFeedService {
    // Reserved internal service identity, never a Cognito subject or app_user ID.
    private static final UUID APPLICATION_FEED_ID =
        UUID.fromString("ff000000-0000-4000-8000-000000000001");
    private static final Duration RETRY_BACKOFF = Duration.ofSeconds(20);

    private final OwnerAngelOneSsmService ssm;
    private final CustomerAngelOneSessionService sessions;
    private final CustomerAngelOneLiveQuoteService liveQuotes;
    private Instant retryAfter = Instant.EPOCH;

    public AppAngelOneMarketFeedService(OwnerAngelOneSsmService ssm,
            CustomerAngelOneSessionService sessions,
            CustomerAngelOneLiveQuoteService liveQuotes) {
        this.ssm = ssm;
        this.sessions = sessions;
        this.liveQuotes = liveQuotes;
    }

    public boolean available() {
        return ssm.available();
    }

    public BigDecimal quote(Instrument instrument) {
        ensureSession();
        return sessions.quote(APPLICATION_FEED_ID, instrument);
    }

    public CustomerAngelOneLiveQuoteService.LiveQuote live(Instrument instrument) {
        ensureSession();
        var result = liveQuotes.quote(APPLICATION_FEED_ID, instrument);
        // An expired backend feed is a provider outage, not a customer's
        // responsibility to enter their personal broker credentials.
        if ("BROKER_CONNECTION_REQUIRED".equals(result.status())) {
            return new CustomerAngelOneLiveQuoteService.LiveQuote(
                result.instrumentId(), result.exchange(), result.tradingSymbol(),
                null, null, null, "UNAVAILABLE", null);
        }
        return result;
    }

    private synchronized void ensureSession() {
        if (!ssm.available()) {
            throw new MarketDataException("PROVIDER_NOT_CONFIGURED",
                "Application market feed is not configured");
        }
        if (sessions.status(APPLICATION_FEED_ID).connected()) return;
        Instant now = Instant.now();
        if (now.isBefore(retryAfter)) {
            throw new MarketDataException("PROVIDER_UNAVAILABLE",
                "Application market feed is reconnecting");
        }
        try {
            var connection = ssm.connect(APPLICATION_FEED_ID);
            if (connection == null || !connection.connected()) {
                throw new MarketDataException("PROVIDER_UNAVAILABLE",
                    "Application market feed could not authenticate");
            }
            retryAfter = Instant.EPOCH;
        } catch (RuntimeException error) {
            // Keep AWS and broker credential material out of exception messages.
            retryAfter = now.plus(RETRY_BACKOFF);
            throw new MarketDataException("PROVIDER_UNAVAILABLE",
                "Application market feed is unavailable");
        }
    }
}
