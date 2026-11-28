package in.aitradingsystem.provider.upstox;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import in.aitradingsystem.instrument.Instrument;
import in.aitradingsystem.instrument.UpstoxBseCrosswalkRepository;
import in.aitradingsystem.provider.upstox.UpstoxApiClient.UpstoxProviderException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * Customer-bound Upstox OAuth authorization-code flow and account reads.
 * Only the platform has the OAuth client secret. Users authenticate on Upstox.
 *
 * <p>Pending state and access tokens are deliberately process-local for this
 * first integration: no unencrypted tokens or refresh credentials are stored
 * in PostgreSQL, frontend, logs, or the OAuth callback page. Multi-replica
 * production deployment requires a shared encrypted session store and
 * transactional one-time state consumption. The private one-shot order
 * transport has no customer route or execution coordinator wired.
 */
@Service
public class CustomerUpstoxOAuthService {
    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    private static final LocalTime EXPIRES = LocalTime.of(3, 30);
    // Allow time for the official Upstox login and two-factor challenge.
    // State remains one-use and customer-bound; 15 minutes is a strict upper bound.
    private static final long STATE_TTL_SECONDS = 900;
    private static final long FAILURE_TTL_SECONDS = 600;
    private static final int MAX_PENDING = 10_000;
    private final SecureRandom secureRandom = new SecureRandom();
    private final ConcurrentHashMap<String, Pending> pending = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, String> mostRecent = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, BrokerSession> sessions = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, AuthFailure> failures = new ConcurrentHashMap<>();
    private final UpstoxProperties props;
    private final UpstoxApiClient api;
    private final Clock clock;
    private final UpstoxNseEquityIsinSource nseEquities;
    private final UpstoxBseCrosswalkRepository bseEquities;

    @Autowired
    public CustomerUpstoxOAuthService(UpstoxProperties props, UpstoxApiClient api,
            UpstoxNseEquityIsinSource nseEquities,
            UpstoxBseCrosswalkRepository bseEquities) {
        this(props, api, Clock.systemUTC(), nseEquities, bseEquities);
    }

    CustomerUpstoxOAuthService(UpstoxProperties props, UpstoxApiClient api, Clock clock) {
        this(props, api, clock, null, null);
    }

    CustomerUpstoxOAuthService(UpstoxProperties props, UpstoxApiClient api,
            Clock clock, UpstoxNseEquityIsinSource nseEquities,
            UpstoxBseCrosswalkRepository bseEquities) {
        this.props = props;
        this.api = api;
        this.clock = clock;
        this.nseEquities = nseEquities;
        this.bseEquities = bseEquities;
    }

    private record Pending(UUID customerId, Instant expiresAt) {}
    private record AuthFailure(String code, Instant expiresAt) {}
    private record BrokerSession(String accessToken, String brokerUserId, Instant expiresAt) {
        @Override public String toString() { return "BrokerSession[redacted]"; }
    }
    public record StartResponse(String authorizationUrl) {}
    public record Status(boolean available, boolean connected, String broker,
                         String accountMasked, Instant expiresAt, boolean orderExecutionEnabled,
                         String lastAuthError) {
        /** Preserve existing account-status callers and owner TOTP fixtures. */
        public Status(boolean available, boolean connected, String broker,
                      String accountMasked, Instant expiresAt, boolean orderExecutionEnabled) {
            this(available, connected, broker, accountMasked, expiresAt,
                 orderExecutionEnabled, null);
        }
    }
    public record AccountData(String provider, Instant fetchedAt, JsonNode data) {}
    public record EquityMarketQuote(String instrumentKey, java.math.BigDecimal lastPrice,
            java.math.BigDecimal bestBid, java.math.BigDecimal bestAsk,
            Instant exchangeAt, Instant receivedAt) {}

    /**
     * Broker-reported NFO entitlement only. A positive result is NOT an
     * authorization to execute a trade: fixed egress IP, available margin,
     * contract freshness, owner consent and reconciliation remain separate.
     */
    public record FnoEntitlement(boolean connected, boolean nfoEnabled,
            boolean limitEnabled, boolean intradayEnabled,
            boolean carryforwardEnabled, String status) {
        public boolean supportsLongOptionLimits() {
            return connected && nfoEnabled && limitEnabled
                && (intradayEnabled || carryforwardEnabled);
        }
    }

    /** Owner-profile permissions for cash-equity intraday LIMIT orders only. */
    public record EquityEntitlement(boolean connected, boolean nseEnabled,
            boolean bseEnabled, boolean limitEnabled, boolean intradayEnabled,
            String status) {
        public boolean supportsIntradayLimits() {
            return connected && (nseEnabled || bseEnabled) && limitEnabled && intradayEnabled;
        }
        public boolean supportsIntradayLimits(String exchange) {
            boolean exchangeEnabled = "NSE".equals(exchange) ? nseEnabled
                : "BSE".equals(exchange) && bseEnabled;
            return connected && exchangeEnabled && limitEnabled && intradayEnabled;
        }
    }

    public StartResponse start(UUID customerId) {
        if (!props.configured()) {
            throw new UpstoxProviderException("UPSTOX_APP_NOT_CONFIGURED");
        }
        Instant now = clock.instant();
        failures.remove(customerId);
        pending.entrySet().removeIf(entry -> now.isAfter(entry.getValue().expiresAt()));
        if (pending.size() >= MAX_PENDING) {
            throw new UpstoxProviderException("UPSTOX_AUTH_CAPACITY_REACHED");
        }
        byte[] random = new byte[32];
        secureRandom.nextBytes(random);
        String state = Base64.getUrlEncoder().withoutPadding().encodeToString(random);
        String hashed = sha256(state);
        String previous = mostRecent.put(customerId, hashed);
        if (previous != null) pending.remove(previous);
        pending.put(hashed, new Pending(customerId, now.plusSeconds(STATE_TTL_SECONDS)));
        String url = "https://api.upstox.com/v2/login/authorization/dialog"
            + "?response_type=code"
            + "&client_id=" + encode(props.clientId())
            + "&redirect_uri=" + encode(props.redirectUri())
            + "&state=" + encode(state);
        return new StartResponse(url);
    }

    /**
     * Callback has no MintTick JWT; the unpredictable, one-time state binds
     * the Upstox response to the initiating authenticated customer UUID.
     */
    public void complete(String state, String code) {
        if (state == null || !state.matches("[A-Za-z0-9_-]{43}")) {
            throw new UpstoxProviderException("UPSTOX_OAUTH_STATE_INVALID");
        }
        String hashed = sha256(state);
        Pending flow = pending.remove(hashed);
        if (flow == null || !mostRecent.remove(flow.customerId(), hashed)
                || !clock.instant().isBefore(flow.expiresAt())) {
            throw new UpstoxProviderException("UPSTOX_OAUTH_STATE_INVALID");
        }
        try {
            if (code == null || code.isBlank() || code.length() > 2048) {
                throw new UpstoxProviderException("UPSTOX_AUTH_DECLINED");
            }
            JsonNode response = api.exchangeCode(props, code);
            installVerifiedToken(flow.customerId(), response);
            failures.remove(flow.customerId());
        } catch (UpstoxProviderException rejected) {
            // Only fixed internal codes are stored. Never retain the OAuth state,
            // authorization code, provider response, access token, or API secret.
            failures.entrySet().removeIf(entry -> !clock.instant().isBefore(entry.getValue().expiresAt()));
            if (failures.size() < MAX_PENDING) {
                failures.put(flow.customerId(),
                    new AuthFailure(publicFailureCode(rejected.code()),
                        clock.instant().plusSeconds(FAILURE_TTL_SECONDS)));
            }
            throw rejected;
        }
    }

    /**
     * Owner-only optional direct Upstox token flow. The controller separately
     * verifies the exact Cognito owner subject and ACTIVE user account before
     * the caller can reach this method. No token is sent to a browser.
     */
    public Status connectOwnerTotp(UUID owner, JsonNode response) {
        if (owner == null || response == null || !props.configured()) {
            throw new UpstoxProviderException("UPSTOX_TOTP_AUTH_UNAVAILABLE");
        }
        installVerifiedToken(owner, response);
        return status(owner);
    }

    private void installVerifiedToken(UUID customerId, JsonNode response) {
        String token = response.path("access_token").asText("");
        if (token.isBlank() || token.length() > 16384) {
            throw new UpstoxProviderException("UPSTOX_AUTH_FAILED");
        }
        // Upstox's token endpoint already supplies the verified account
        // profile alongside access_token. Avoid a second HTTPS round trip on
        // the time-limited public OAuth callback. For older/incomplete token
        // responses, Get Profile supplies these fields under "data".
        JsonNode profile = response;
        if (!hasProfileIdentity(response)) {
            JsonNode profileResult = api.profile(token);
            profile = profileResult.path("data").isObject()
                    ? profileResult.path("data") : profileResult;
        }
        String broker = profile.path("broker").asText("");
        String brokerUser = profile.path("user_id").asText("");
        if (!"UPSTOX".equalsIgnoreCase(broker) || brokerUser.isBlank()
                || !profile.path("is_active").asBoolean(false)) {
            throw new UpstoxProviderException("UPSTOX_PROFILE_REJECTED");
        }
        for (var existing : sessions.entrySet()) {
            if (!existing.getKey().equals(customerId)
                    && !clock.instant().isAfter(existing.getValue().expiresAt())
                    && existing.getValue().brokerUserId().equals(brokerUser)) {
                throw new UpstoxProviderException("UPSTOX_ACCOUNT_ALREADY_CONNECTED");
            }
        }
        Instant expires = nextTokenExpiry(clock.instant());
        sessions.put(customerId, new BrokerSession(token, brokerUser, expires));
        failures.remove(customerId);
    }

    /** All three fields must be present before trusting the token profile. */
    private static boolean hasProfileIdentity(JsonNode profile) {
        return profile != null && profile.isObject()
            && profile.path("broker").isTextual()
            && profile.path("user_id").isTextual()
            && profile.path("is_active").isBoolean();
    }

    public Status status(UUID customerId) {
        BrokerSession session = validSession(customerId);
        AuthFailure failure = failures.get(customerId);
        if (failure != null && !clock.instant().isBefore(failure.expiresAt())) {
            failures.remove(customerId, failure);
            failure = null;
        }
        return new Status(props.configured(), session != null, "UPSTOX",
            session == null ? null : mask(session.brokerUserId()),
            session == null ? null : session.expiresAt(), false,
            session != null || failure == null ? null : failure.code());
    }

    public void disconnect(UUID customerId) {
        sessions.remove(customerId);
        failures.remove(customerId);
        String hashed = mostRecent.remove(customerId);
        if (hashed != null) pending.remove(hashed);
    }

    public AccountData funds(UUID user) { return read(user, api::funds); }
    public AccountData holdings(UUID user) { return read(user, api::holdings); }
    public AccountData positions(UUID user) { return read(user, api::positions); }
    public AccountData orders(UUID user) { return read(user, api::orders); }
    public AccountData staticIps(UUID user) { return read(user, api::staticIps); }

    /** Read-only official fundamentals using the existing linked customer OAuth session. */
    public AccountData fundamentals(UUID user, String isin, String section) {
        return read(user, token -> api.fundamentals(token, isin, section));
    }

    public AccountData orderHistoryByTag(UUID owner, String tag) {
        if (tag == null || !tag.matches("[A-Za-z0-9_-]{8,20}")) {
            throw new UpstoxProviderException("UPSTOX_ORDER_TAG_INVALID");
        }
        return read(owner, token -> api.orderHistoryByTag(token, tag));
    }

    /** Authenticated owner session only; never accepts the SSM Analytics token. */
    public record FnoSubmission(String outcome, java.util.List<String> orderIds, String code) {
        public FnoSubmission { orderIds = java.util.List.copyOf(orderIds); }
    }

    /**
     * Exactly one broker POST, no retry. This method is only callable by a
     * coordinator after it has committed a durable owner-bound SUBMITTING row
     * and verified the NFO contract, session, egress IP, cash and user consent.
     */
    public FnoSubmission submitVerifiedFnoOnce(UUID owner, ObjectNode payload) {
        BrokerSession session = validSession(owner);
        if (session == null) {
            return new FnoSubmission("REJECTED", java.util.List.of(),
                "UPSTOX_SESSION_MISSING");
        }
        org.springframework.http.client.SimpleClientHttpRequestFactory factory =
            new org.springframework.http.client.SimpleClientHttpRequestFactory();
        // Keep one-shot broker execution within the account Lambda's 7s limit.
        // A timeout is UNKNOWN, not permission to retry.
        factory.setConnectTimeout(2000);
        factory.setReadTimeout(4000);
        var transport = new UpstoxFnoLiveOrderTransport(
            org.springframework.web.client.RestClient.builder().requestFactory(factory));
        var response = transport.submitOnce(session.accessToken(), payload);
        if ("UPSTOX_SESSION_EXPIRED".equals(response.code())) {
            sessions.remove(owner, session);
        }
        return new FnoSubmission(response.outcome().name(),
            response.orderIds(), response.code());
    }

    /**
     * One-shot customer-session transport for cash intraday LIMIT orders.
     * Only an internal coordinator may call this after committing its durable
     * order claim and passing independent compliance, quote and risk gates.
     */
    public record EquitySubmission(String outcome, String orderId, String code) {}

    public EquitySubmission submitVerifiedEquityIntradayOnce(UUID owner,
            ObjectNode payload) {
        BrokerSession session = validSession(owner);
        if (session == null) {
            return new EquitySubmission("REJECTED", null, "UPSTOX_SESSION_MISSING");
        }
        org.springframework.http.client.SimpleClientHttpRequestFactory factory =
            new org.springframework.http.client.SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(2000);
        factory.setReadTimeout(4000);
        var transport = new UpstoxIntradayEquityOrderTransport(
            org.springframework.web.client.RestClient.builder().requestFactory(factory));
        var response = transport.submitOnce(session.accessToken(), payload);
        if ("UPSTOX_SESSION_EXPIRED".equals(response.code())) {
            sessions.remove(owner, session);
        }
        return new EquitySubmission(response.outcome().name(),
            response.orderId(), response.code());
    }

    /**
     * Read one fresh, customer-session Upstox cash-equity snapshot. This is
     * data only: it does not grant order permission or dispatch an order.
     */
    /**
     * Resolve the exact Upstox cash-equity instrument key from the fresh,
     * source-custodied reference. Null-free: any gap is an exception, never a guess.
     */
    public String cashEquityInstrumentKey(Instrument instrument) {
        if (instrument == null
                || !"EQ".equalsIgnoreCase(instrument.instrumentType())) {
            throw new UpstoxProviderException("UPSTOX_EQUITY_INSTRUMENT_UNVERIFIED");
        }
        if ("NSE".equals(instrument.exchange()) && nseEquities != null) {
            String isin = nseEquities.resolve(instrument.exchange(), instrument.tradingSymbol())
                .orElseThrow(() -> new UpstoxProviderException(
                    "UPSTOX_EQUITY_INSTRUMENT_REFERENCE_STALE_OR_MISSING"));
            return "NSE_EQ|" + isin;
        }
        if ("BSE".equals(instrument.exchange()) && bseEquities != null) {
            return bseEquities.recentInstrumentKey(
                instrument.symbolToken(), instrument.tradingSymbol())
                .orElseThrow(() -> new UpstoxProviderException(
                    "UPSTOX_EQUITY_INSTRUMENT_REFERENCE_STALE_OR_MISSING"));
        }
        throw new UpstoxProviderException("UPSTOX_EQUITY_INSTRUMENT_UNVERIFIED");
    }

    public EquityMarketQuote cashEquityQuote(UUID owner, Instrument instrument) {
        String instrumentKey = cashEquityInstrumentKey(instrument);
        if (!equityEntitlement(owner).supportsIntradayLimits(instrument.exchange())) {
            throw new UpstoxProviderException("UPSTOX_EQUITY_INTRADAY_PERMISSION_NOT_VERIFIED");
        }
        BrokerSession session = validSession(owner);
        if (session == null) {
            throw new UpstoxProviderException("UPSTOX_SESSION_MISSING");
        }
        JsonNode data;
        try {
            data = api.cashEquityQuote(session.accessToken(), instrumentKey);
        } catch (UpstoxProviderException unavailable) {
            if ("UPSTOX_SESSION_EXPIRED".equals(unavailable.code())) {
                sessions.remove(owner, session);
            }
            throw unavailable;
        }
        JsonNode quote = null;
        var values = data == null ? null : data.elements();
        if (values != null) {
            while (values.hasNext()) {
                JsonNode candidate = values.next();
                if (instrumentKey.equals(candidate.path("instrument_token").asText())) {
                    quote = candidate;
                    break;
                }
            }
        }
        if (quote == null || !quote.path("last_price").isNumber()) {
            throw new UpstoxProviderException("UPSTOX_EQUITY_QUOTE_UNVERIFIED");
        }
        java.math.BigDecimal ltp = quote.path("last_price").decimalValue();
        java.math.BigDecimal bid = quote.path("depth").path("buy").path(0)
            .path("price").decimalValue();
        java.math.BigDecimal ask = quote.path("depth").path("sell").path(0)
            .path("price").decimalValue();
        Instant exchangeAt;
        try {
            exchangeAt = OffsetDateTime.parse(quote.path("timestamp").asText()).toInstant();
        } catch (RuntimeException invalidTimestamp) {
            throw new UpstoxProviderException("UPSTOX_EQUITY_QUOTE_TIME_INVALID");
        }
        Instant receivedAt = clock.instant();
        if (ltp.signum() <= 0 || bid.signum() <= 0 || ask.signum() <= 0
                || bid.compareTo(ask) > 0
                || exchangeAt.isAfter(receivedAt.plusSeconds(2))
                || exchangeAt.isBefore(receivedAt.minusSeconds(5))) {
            throw new UpstoxProviderException("UPSTOX_EQUITY_QUOTE_STALE_OR_INVALID");
        }
        return new EquityMarketQuote(instrumentKey, ltp, bid, ask, exchangeAt, receivedAt);
    }


    /**
     * Read the authenticated Upstox profile on demand, never from app-level
     * Analytics credentials or a caller-provided account claim. Only sanitized
     * booleans are returned; tokens and raw profile data are never exposed.
     */
    public FnoEntitlement fnoEntitlement(UUID customerId) {
        BrokerSession session = validSession(customerId);
        if (session == null) {
            return new FnoEntitlement(false, false, false, false, false,
                "UPSTOX_NOT_CONNECTED");
        }
        try {
            JsonNode profile = api.profile(session.accessToken());
            if (profile == null || !profile.isObject()
                    || !"UPSTOX".equalsIgnoreCase(profile.path("broker").asText(""))
                    || !session.brokerUserId().equals(profile.path("user_id").asText(""))
                    || !profile.path("is_active").asBoolean(false)) {
                return new FnoEntitlement(true, false, false, false, false,
                    "UPSTOX_PROFILE_UNVERIFIED");
            }
            boolean nfo = hasProfilePermission(profile, "exchanges", "NFO");
            boolean limit = hasProfilePermission(profile, "order_types", "LIMIT");
            boolean intraday = hasProfilePermission(profile, "products", "I");
            boolean carryforward = hasProfilePermission(profile, "products", "D");
            String status = !nfo ? "UPSTOX_NFO_NOT_ENABLED"
                : !limit ? "UPSTOX_LIMIT_NOT_ENABLED"
                : !intraday && !carryforward ? "UPSTOX_FNO_PRODUCT_NOT_ENABLED"
                : "UPSTOX_NFO_PROFILE_VERIFIED";
            return new FnoEntitlement(true, nfo, limit, intraday, carryforward, status);
        } catch (UpstoxProviderException unavailable) {
            if ("UPSTOX_SESSION_EXPIRED".equals(unavailable.code())) {
                sessions.remove(customerId, session);
            }
            return new FnoEntitlement(false, false, false, false, false,
                "UPSTOX_PROFILE_UNAVAILABLE");
        }
    }

    /** Profile permission evidence is informative and never enables dispatch by itself. */
    public EquityEntitlement equityEntitlement(UUID customerId) {
        BrokerSession session = validSession(customerId);
        if (session == null) {
            return new EquityEntitlement(false, false, false, false, false,
                "UPSTOX_NOT_CONNECTED");
        }
        try {
            JsonNode profile = api.profile(session.accessToken());
            if (profile == null || !profile.isObject()
                    || !"UPSTOX".equalsIgnoreCase(profile.path("broker").asText(""))
                    || !session.brokerUserId().equals(profile.path("user_id").asText(""))
                    || !profile.path("is_active").asBoolean(false)) {
                return new EquityEntitlement(true, false, false, false, false,
                    "UPSTOX_PROFILE_UNVERIFIED");
            }
            boolean nse = hasProfilePermission(profile, "exchanges", "NSE");
            boolean bse = hasProfilePermission(profile, "exchanges", "BSE");
            boolean limit = hasProfilePermission(profile, "order_types", "LIMIT");
            boolean intraday = hasProfilePermission(profile, "products", "I");
            String status = !nse && !bse ? "UPSTOX_EQUITY_EXCHANGE_NOT_ENABLED"
                : !limit ? "UPSTOX_LIMIT_NOT_ENABLED"
                : !intraday ? "UPSTOX_INTRADAY_PRODUCT_NOT_ENABLED"
                : "UPSTOX_EQUITY_PROFILE_VERIFIED";
            return new EquityEntitlement(true, nse, bse, limit, intraday, status);
        } catch (UpstoxProviderException unavailable) {
            if ("UPSTOX_SESSION_EXPIRED".equals(unavailable.code())) {
                sessions.remove(customerId, session);
            }
            return new EquityEntitlement(false, false, false, false, false,
                "UPSTOX_PROFILE_UNAVAILABLE");
        }
    }

    private static boolean hasProfilePermission(JsonNode profile, String field,
            String expected) {
        JsonNode values = profile.path(field);
        if (!values.isArray() || values.size() > 64) return false;
        for (JsonNode value : values) {
            if (value.isTextual() && expected.equals(value.asText())) return true;
        }
        return false;
    }

    private AccountData read(UUID customerId, BrokerRead operation) {
        BrokerSession session = validSession(customerId);
        if (session == null) throw new UpstoxProviderException("UPSTOX_NOT_CONNECTED");
        try {
            JsonNode result = operation.fetch(session.accessToken());
            return new AccountData("UPSTOX", clock.instant(), Objects.requireNonNull(result));
        } catch (UpstoxProviderException ex) {
            if ("UPSTOX_SESSION_EXPIRED".equals(ex.code())) {
                sessions.remove(customerId, session);
            }
            throw ex;
        }
    }

    private BrokerSession validSession(UUID user) {
        BrokerSession existing = sessions.get(user);
        if (existing != null && !clock.instant().isBefore(existing.expiresAt())) {
            sessions.remove(user, existing);
            return null;
        }
        return existing;
    }

    private static String publicFailureCode(String code) {
        return switch (code) {
            case "UPSTOX_AUTH_DECLINED", "UPSTOX_AUTH_CODE_INVALID",
                 "UPSTOX_OAUTH_APP_CREDENTIALS_INVALID",
                 "UPSTOX_OAUTH_REDIRECT_REJECTED",
                 "UPSTOX_PROFILE_REJECTED", "UPSTOX_ACCOUNT_SEGMENTS_INACTIVE",
                 "UPSTOX_ACCOUNT_ALREADY_CONNECTED",
                 "UPSTOX_AUTH_UNAVAILABLE", "UPSTOX_AUTH_TRANSPORT_UNAVAILABLE",
                 "UPSTOX_AUTH_INVALID_RESPONSE", "UPSTOX_AUTH_CLIENT_FAILURE" -> code;
            default -> "UPSTOX_AUTH_FAILED";
        };
    }

    private static Instant nextTokenExpiry(Instant from) {
        ZonedDateTime local = from.atZone(IST);
        var date = local.toLocalTime().isBefore(EXPIRES)
            ? local.toLocalDate() : local.toLocalDate().plusDays(1);
        return date.atTime(EXPIRES).atZone(IST).toInstant();
    }

    private static String mask(String identifier) {
        return "***" + identifier.substring(Math.max(0, identifier.length() - 3));
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 unavailable", ex);
        }
    }

    @FunctionalInterface private interface BrokerRead {
        JsonNode fetch(String token);
    }
}
