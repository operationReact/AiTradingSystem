package in.aitradingsystem.provider.angelone;

import in.aitradingsystem.instrument.Instrument;
import in.aitradingsystem.marketdata.MarketDataException;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * Application-owned, backend-only SmartAPI data session for MintTick's private
 * dev test. Its identity is NOT a customer or a broker trading authorization.
 * Reads the same existing SSM parameters used for the owner's optional
 * account connection. No credentials are sent to Flutter or other customers.
 *
 * The server uses bounded, shared application-owned WebSocket shards. Individual
 * quote reads follow the existing token subscription rather than opening a personal broker stream.
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
    // The last stable failure code, so callers inside the backoff window still see the real reason.
    private String lastReasonCode = "PROVIDER_UNAVAILABLE";

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

    /** Application-session FULL quote; the parser verifies identity, feed time and OHLC ordering. */
    public AngelOneQuoteDetailParser.Detail detail(Instrument instrument) {
        ensureSession();
        return sessions.quoteDetail(APPLICATION_FEED_ID, instrument);
    }

    /** Existing NSE-only FULL batch for index/mover overview and research callers. */
    public Map<String, AngelOneQuoteDetailParser.Detail> details(List<AngelOneQuoteDetailParser.Expected> expected) {
        return details("NSE", expected);
    }

    /** App-owned read-only FULL quote for one cash exchange, not an end-user broker connection. */
    public Map<String, AngelOneQuoteDetailParser.Detail> details(String exchange,
            List<AngelOneQuoteDetailParser.Expected> expected) {
        if (!"NSE".equals(exchange) && !"BSE".equals(exchange)) {
            throw new IllegalArgumentException("FULL batch exchange must be NSE or BSE");
        }
        ensureSession();
        return sessions.quoteDetailsBatch(APPLICATION_FEED_ID, exchange, expected);
    }

    /** Source-authenticated private history; shares the SSM app session and rate limiter. */
    public com.fasterxml.jackson.databind.JsonNode historicalCandles(String exchange, String token,
                                     String interval, String from, String to) {
        ensureSession();
        return sessions.historicalCandles(APPLICATION_FEED_ID, exchange, token, interval, from, to);
    }

    public CustomerAngelOneLiveQuoteService.LiveQuote live(Instrument instrument) {
        ensureSession();
        return withoutPersonalBrokerPrompt(
            liveQuotes.snapshotApplication(APPLICATION_FEED_ID, List.of(instrument)).getFirst());
    }

    /** One live result per instrument, sharing the single application-owned stream. */
    public List<CustomerAngelOneLiveQuoteService.LiveQuote> snapshot(List<Instrument> instruments) {
        ensureSession();
        return liveQuotes.snapshotApplication(APPLICATION_FEED_ID, instruments).stream()
            .map(AppAngelOneMarketFeedService::withoutPersonalBrokerPrompt).toList();
    }

    // An expired backend feed is a provider outage, not a customer's
    // responsibility to enter their personal broker credentials.
    private static CustomerAngelOneLiveQuoteService.LiveQuote withoutPersonalBrokerPrompt(
            CustomerAngelOneLiveQuoteService.LiveQuote result) {
        if (!"BROKER_CONNECTION_REQUIRED".equals(result.status())) return result;
        return new CustomerAngelOneLiveQuoteService.LiveQuote(
            result.instrumentId(), result.exchange(), result.tradingSymbol(),
            null, null, null, "UNAVAILABLE", null);
    }

    private synchronized void ensureSession() {
        if (!ssm.available()) {
            throw new MarketDataException("PROVIDER_NOT_CONFIGURED",
                "Application market feed is not configured");
        }
        if (sessions.status(APPLICATION_FEED_ID).connected()) return;
        Instant now = Instant.now();
        if (now.isBefore(retryAfter)) {
            throw new MarketDataException(lastReasonCode, "Application market feed is reconnecting");
        }
        try {
            var connection = ssm.connect(APPLICATION_FEED_ID);
            if (connection == null || !connection.connected()) {
                lastReasonCode = "PROVIDER_LOGIN_REJECTED";
                throw new MarketDataException(lastReasonCode, "Application market feed could not authenticate");
            }
            retryAfter = Instant.EPOCH;
        } catch (RuntimeException error) {
            // Keep AWS and broker credential material out of exception messages. The stable reason CODE
            // (for example OWNER_SSM_ACCESS_UNAVAILABLE or a login code) is kept so operators can act on it.
            retryAfter = now.plus(RETRY_BACKOFF);
            lastReasonCode = reasonCode(error);
            throw new MarketDataException(lastReasonCode, "Application market feed is unavailable");
        }
    }

    /** Stable, secret-free reason code only. Messages and credential material are never propagated. */
    static String reasonCode(RuntimeException error) {
        String code = null;
        if (error instanceof MarketDataException marketData) code = marketData.code();
        if (error instanceof AngelOneException angel) code = angel.code();
        if (code != null && code.matches("[A-Z][A-Z0-9_]{1,59}")) return code;
        // Uncoded failures get a stable CLASS-level code only. Messages are never used.
        if (error instanceof IllegalArgumentException) return "PROVIDER_INPUT_INVALID";
        if (error instanceof IllegalStateException) return "PROVIDER_STATE_ERROR";
        if (error instanceof org.springframework.web.client.RestClientException) return "PROVIDER_HTTP_CLIENT_ERROR";
        return "PROVIDER_CALL_FAILED";
    }
}
