package in.aitradingsystem.provider.upstox;

import com.fasterxml.jackson.databind.JsonNode;
import in.aitradingsystem.provider.upstox.UpstoxApiClient.UpstoxProviderException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalTime;
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
 * Customer-bound Upstox OAuth authorization-code flow (read-only).
 * Only the platform has the OAuth client secret. Users authenticate on Upstox.
 *
 * <p>Pending state and access tokens are deliberately process-local for this
 * first integration: no unencrypted tokens or refresh credentials are stored
 * in PostgreSQL, frontend, logs, or the OAuth callback page. Multi-replica
 * production deployment requires a shared encrypted session store and
 * transactional one-time state consumption.
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

    @Autowired
    public CustomerUpstoxOAuthService(UpstoxProperties props, UpstoxApiClient api) {
        this(props, api, Clock.systemUTC());
    }

    CustomerUpstoxOAuthService(UpstoxProperties props, UpstoxApiClient api, Clock clock) {
        this.props = props;
        this.api = api;
        this.clock = clock;
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
