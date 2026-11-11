package in.aitradingsystem.provider.upstox;

import in.aitradingsystem.provider.upstox.UpstoxApiClient.UpstoxProviderException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.ssm.SsmClient;
import software.amazon.awssdk.services.ssm.model.GetParametersRequest;
import software.amazon.awssdk.services.ssm.model.GetParametersResponse;
import software.amazon.awssdk.services.ssm.model.ParameterType;

/**
 * Only the explicitly Cognito-bound owner can call this from the controller.
 * Optional for Upstox "individual" developer apps supporting the published
 * PIN + TOTP token endpoint. NEVER auto-login non-owner customers.
 *
 * No PIN/TOTP seed/broker access token is stored outside process/SSM,
 * logged, included in an exception or made available to a client.
 */
@Service
public final class OwnerUpstoxTotpService {
    private static final String PREFIX = "/minttick/upstox/";
    private static final String PIN = PREFIX + "login-pin";
    private static final String TOTP_SEED = PREFIX + "totp-secret";
    private static final List<String> PARAMS = List.of(PIN, TOTP_SEED);
    private final CustomerUpstoxOAuthService sessions;
    private final UpstoxProperties props;
    private final UpstoxApiClient api;
    private final boolean enabled;
    private final Clock clock;
    private final SsmClient suppliedClient;
    private final Object loginLock = new Object();
    private Instant lastAttempt;

    @Autowired
    public OwnerUpstoxTotpService(CustomerUpstoxOAuthService sessions,
            UpstoxProperties props, UpstoxApiClient api,
            @Value("${MINTTICK_UPSTOX_OWNER_TOTP_ENABLED:false}") boolean enabled) {
        this(sessions, props, api, enabled, Clock.systemUTC(), null);
    }

    OwnerUpstoxTotpService(CustomerUpstoxOAuthService sessions,
            UpstoxProperties props, UpstoxApiClient api, boolean enabled,
            Clock clock, SsmClient suppliedClient) {
        this.sessions = sessions;
        this.props = props;
        this.api = api;
        this.enabled = enabled;
        this.clock = clock;
        this.suppliedClient = suppliedClient;
    }

    public boolean available() {
        // Only the owner-gated endpoint may expose this boolean. Actual
        // SSM access and broker eligibility are verified on first use.
        return enabled && props.configured();
    }

    public CustomerUpstoxOAuthService.Status connect(UUID owner) {
        if (!available() || owner == null) {
            throw fail("UPSTOX_OWNER_AUTO_LOGIN_DISABLED");
        }
        synchronized (loginLock) {
            var current = sessions.status(owner);
            if (current.connected()) return current; // Do not revoke live token.
            Instant now = clock.instant();
            if (lastAttempt != null && now.isBefore(lastAttempt.plus(Duration.ofSeconds(90)))) {
                throw fail("UPSTOX_OWNER_LOGIN_COOLDOWN");
            }
            lastAttempt = now;
            Map<String, String> params = parameters();
            String pin = params.get(PIN);
            if (pin == null || !pin.matches("[0-9]{6}")) {
                throw fail("UPSTOX_OWNER_SSM_INVALID");
            }
            final String code;
            try {
                code = UpstoxTotp.generate(params.get(TOTP_SEED), now);
            } catch (RuntimeException exception) {
                throw fail("UPSTOX_OWNER_SSM_INVALID");
            }
            // Upstox only enables this endpoint for individual apps.
            // Broker errors fail closed; regular OAuth remains available.
            return sessions.connectOwnerTotp(owner,
                    api.ownerTokenFromTotp(props.clientId(), pin, code));
        }
    }

    private Map<String, String> parameters() {
        GetParametersResponse response;
        try {
            if (suppliedClient != null) {
                response = read(suppliedClient);
            } else {
                try (SsmClient client = SsmClient.builder()
                        .region(Region.AP_SOUTH_1)
                        .overrideConfiguration(c -> c.apiCallTimeout(Duration.ofSeconds(12)))
                        .build()) {
                    response = read(client);
                }
            }
        } catch (RuntimeException ex) {
            throw fail("UPSTOX_OWNER_SSM_UNAVAILABLE");
        }
        if (response == null || response.parameters().size() != 2
                || !response.invalidParameters().isEmpty()) {
            throw fail("UPSTOX_OWNER_SSM_INVALID");
        }
        Map<String, String> values = new HashMap<>();
        for (var entry : response.parameters()) {
            if (entry == null || !PARAMS.contains(entry.name())
                    || entry.type() != ParameterType.SECURE_STRING
                    || entry.value() == null || entry.value().isBlank()
                    || values.putIfAbsent(entry.name(), entry.value()) != null) {
                throw fail("UPSTOX_OWNER_SSM_INVALID");
            }
        }
        if (values.size() != 2) throw fail("UPSTOX_OWNER_SSM_INVALID");
        return values;
    }

    private static GetParametersResponse read(SsmClient client) {
        return client.getParameters(GetParametersRequest.builder().names(PARAMS)
            .withDecryption(true).build());
    }

    private static UpstoxProviderException fail(String code) {
        return new UpstoxProviderException(code);
    }
}
