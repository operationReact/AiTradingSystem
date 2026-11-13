package in.aitradingsystem.provider.angelone;

import com.fasterxml.jackson.databind.JsonNode;
import in.aitradingsystem.instrument.Instrument;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.Locale;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Service;

/**
 * Customer-scoped, read-only broker sessions.
 *
 * <p>Only the customer's API key and a short-lived broker JWT are retained
 * in process memory, keyed by authenticated MintTick user UUID. Never stores
 * login PIN, current TOTP, TOTP seed, refresh token, or credentials in the DB.
 * Restart and IST midnight revoke the session. No order execution API exists.
 *
 * <p>A future multi-instance deployment must replace this local session store
 * with a purpose-built secure session service before exposing multiple replicas.
 */
@Service
public class CustomerAngelOneSessionService {
    private static final ZoneId BROKER_ZONE = ZoneId.of("Asia/Kolkata");
    private final ConcurrentHashMap<UUID, BrokerSession> sessions = new ConcurrentHashMap<>();
    private final AngelOneApiClient api;
    private final AngelOneRateLimiter limiter;
    private final Clock clock;

    @org.springframework.beans.factory.annotation.Autowired
    public CustomerAngelOneSessionService(AngelOneApiClient api, AngelOneRateLimiter limiter) {
        this(api, limiter, Clock.systemUTC());
    }

    CustomerAngelOneSessionService(AngelOneApiClient api, AngelOneRateLimiter limiter, Clock clock) {
        this.api = api;
        this.limiter = limiter;
        this.clock = clock;
    }

    public record ConnectionStatus(boolean connected, String broker, String clientCodeMasked,
                                   Instant expiresAt, boolean orderExecutionEnabled) {}

    public record BrokerRead(String provider, Instant fetchedAt, JsonNode data) {}

    private record BrokerSession(String apiKey, String clientCode, String jwt,
                                 String feedToken, Instant expiresAt) {
        @Override public String toString() { return "BrokerSession[redacted]"; }
    }

    public ConnectionStatus connect(UUID userId, String apiKey, String clientCode, String pin, String totp) {
        if (apiKey == null || apiKey.isBlank() || apiKey.length() > 256
                || clientCode == null || !clientCode.matches("(?i)[a-z0-9]{4,20}")
                || pin == null || !pin.matches("[0-9]{4,8}")
                || totp == null || !totp.matches("[0-9]{6}")) {
            throw new IllegalArgumentException("Valid Angel One API key, client code, PIN and current TOTP required");
        }
        String code = clientCode.trim().toUpperCase(Locale.ROOT);
        // Apply the provider's login pace to every individual customer's attempt.
        limiter.acquire(AngelOneRateLimiter.Category.AUTH);
        AngelOneApiClient.LoginTokens tokens = api.customerLogin(apiKey, code, pin, totp);
        JsonNode profile = api.customerProfile(apiKey, tokens.jwtToken());
        String verifiedClient = profile.hasNonNull("clientcode") ? profile.get("clientcode").asText()
                : profile.hasNonNull("clientCode") ? profile.get("clientCode").asText() : "";
        if (!code.equalsIgnoreCase(verifiedClient)) {
            throw new AngelOneException(AngelOneException.Kind.REJECTED, "PROFILE_MISMATCH",
                "Angel One verified profile did not match the requested account");
        }
        Instant now = clock.instant();
        Instant midnight = ZonedDateTime.ofInstant(now, BROKER_ZONE)
            .toLocalDate().plusDays(1).atStartOfDay(BROKER_ZONE).toInstant();
        // Never assume the broker session is valid for the full trading day.
        Instant boundedExpiry = now.plusSeconds(3 * 60 * 60);
        Instant expires = midnight.isBefore(boundedExpiry) ? midnight : boundedExpiry;
        sessions.put(userId, new BrokerSession(apiKey, code, tokens.jwtToken(), tokens.feedToken(), expires));
        return status(userId);
    }

    public ConnectionStatus status(UUID userId) {
        BrokerSession session = current(userId);
        if (session == null) {
            return new ConnectionStatus(false, "ANGEL_ONE", null, null, false);
        }
        String code = session.clientCode();
        String suffix = code.substring(Math.max(0, code.length() - 3));
        return new ConnectionStatus(true, "ANGEL_ONE", "***" + suffix, session.expiresAt(), false);
    }

    /**
     * Only the server-side read-only streaming service can receive these values.
     * No token is serialized in customer APIs, logs, or diagnostics.
     */
    record StreamCredentials(String apiKey, String clientCode, String jwt,
                             String feedToken, Instant expiresAt) {
        @Override public String toString() { return "StreamCredentials[redacted]"; }
    }

    StreamCredentials credentialsForStream(UUID userId) {
        BrokerSession s = current(userId);
        if (s == null || s.feedToken() == null || s.feedToken().isBlank()) {
            return null;
        }
        return new StreamCredentials(s.apiKey(), s.clientCode(), s.jwt(),
                s.feedToken(), s.expiresAt());
    }

    public void disconnect(UUID userId) {
        sessions.remove(userId);
    }

    public BrokerRead funds(UUID userId) {
        return read(userId, AngelOneRateLimiter.Category.ACCOUNT_FUNDS, api::customerFunds);
    }

    public BrokerRead holdings(UUID userId) {
        return read(userId, AngelOneRateLimiter.Category.ACCOUNT_PORTFOLIO, api::customerHoldings);
    }

    public BrokerRead positions(UUID userId) {
        return read(userId, AngelOneRateLimiter.Category.ACCOUNT_PORTFOLIO, api::customerPositions);
    }

    public BrokerRead orders(UUID userId) {
        return read(userId, AngelOneRateLimiter.Category.ACCOUNT_PORTFOLIO, api::customerOrders);
    }

    /** On-demand LTP using only this user's own verified and unexpired broker session. */
    public BigDecimal quote(UUID userId, Instrument instrument) {
        BrokerSession session = current(userId);
        if (session == null) throw new BrokerConnectionRequiredException();
        limiter.acquire(AngelOneRateLimiter.Category.MARKET_QUOTE);
        try {
            return api.customerLtp(session.apiKey(), session.jwt(), instrument.exchange(),
                instrument.tradingSymbol(), instrument.symbolToken());
        } catch (AngelOneException ex) {
            if (ex.kind() == AngelOneException.Kind.SESSION_EXPIRED) {
                sessions.remove(userId, session);
            }
            throw ex;
        }
    }

    /** On-demand FULL quote (OHLC, previous close, volume) for this user's verified session. */
    public AngelOneQuoteDetailParser.Detail quoteDetail(UUID userId, Instrument instrument) {
        BrokerSession session = current(userId);
        if (session == null) throw new BrokerConnectionRequiredException();
        limiter.acquire(AngelOneRateLimiter.Category.MARKET_QUOTE);
        try {
            JsonNode data = "NSE".equals(instrument.exchange())
                ? api.customerFullQuote(session.apiKey(), session.jwt(), instrument.symbolToken())
                : api.customerFullQuote(session.apiKey(), session.jwt(),
                    instrument.exchange(), instrument.symbolToken());
            return AngelOneQuoteDetailParser.parse(data, instrument, clock.instant());
        } catch (AngelOneException ex) {
            if (ex.kind() == AngelOneException.Kind.SESSION_EXPIRED) {
                sessions.remove(userId, session);
            }
            throw ex;
        }
    }

    /** Backward-compatible NSE-only batch for verified index and market overview reads. */
    public Map<String, AngelOneQuoteDetailParser.Detail> quoteDetailsBatch(UUID userId,
            List<AngelOneQuoteDetailParser.Expected> expected) {
        return quoteDetailsBatch(userId, "NSE", expected);
    }

    /** Explicit exchange-scoped FULL batch; responses are matched to exchange, token and symbol. */
    public Map<String, AngelOneQuoteDetailParser.Detail> quoteDetailsBatch(UUID userId, String exchange,
            List<AngelOneQuoteDetailParser.Expected> expected) {
        if (!"NSE".equals(exchange) && !"BSE".equals(exchange)) {
            throw new IllegalArgumentException("FULL batch exchange must be NSE or BSE");
        }
        BrokerSession session = current(userId);
        if (session == null) throw new BrokerConnectionRequiredException();
        limiter.acquire(AngelOneRateLimiter.Category.MARKET_QUOTE);
        try {
            List<String> tokens = expected.stream().map(AngelOneQuoteDetailParser.Expected::symbolToken).toList();
            JsonNode data = "NSE".equals(exchange)
                ? api.customerFullQuotes(session.apiKey(), session.jwt(), tokens)
                : api.customerFullQuotes(session.apiKey(), session.jwt(), exchange, tokens);
            return AngelOneQuoteDetailParser.parseBatch(exchange, data, expected, clock.instant());
        } catch (AngelOneException ex) {
            if (ex.kind() == AngelOneException.Kind.SESSION_EXPIRED) {
                sessions.remove(userId, session);
            }
            throw ex;
        }
    }

    /**
     * Read-only historical OHLCV under the existing verified session. The
     * application feed uses its reserved internal UUID, never a customer ID.
     * Historical request pacing is shared with the regular provider adapter.
     */
    public JsonNode historicalCandles(UUID userId, String exchange, String symbolToken,
                                      String interval, String fromDate, String toDate) {
        BrokerSession session = current(userId);
        if (session == null) throw new BrokerConnectionRequiredException();
        limiter.acquireForRecovery(AngelOneRateLimiter.Category.HISTORICAL_CANDLES);
        try {
            return api.customerCandles(session.apiKey(), session.jwt(), exchange,
                    symbolToken, interval, fromDate, toDate);
        } catch (AngelOneException ex) {
            if (ex.kind() == AngelOneException.Kind.SESSION_EXPIRED) {
                sessions.remove(userId, session);
            }
            throw ex;
        }
    }

    private BrokerRead read(UUID userId, AngelOneRateLimiter.Category category, AccountCall call) {
        BrokerSession session = current(userId);
        if (session == null) throw new BrokerConnectionRequiredException();
        limiter.acquire(category);
        try {
            return new BrokerRead("ANGEL_ONE", clock.instant(),
                call.apply(session.apiKey(), session.jwt()));
        } catch (AngelOneException ex) {
            if (ex.kind() == AngelOneException.Kind.SESSION_EXPIRED) {
                sessions.remove(userId, session);
            }
            throw ex;
        }
    }

    private BrokerSession current(UUID userId) {
        BrokerSession session = sessions.get(userId);
        if (session != null && !clock.instant().isBefore(session.expiresAt())) {
            sessions.remove(userId, session);
            return null;
        }
        return session;
    }

    @FunctionalInterface
    private interface AccountCall {
        JsonNode apply(String apiKey, String jwt);
    }

    public static final class BrokerConnectionRequiredException extends RuntimeException {}
}
