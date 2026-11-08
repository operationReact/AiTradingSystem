package in.aitradingsystem.provider.angelone;

import java.time.Clock;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.ssm.SsmClient;
import software.amazon.awssdk.services.ssm.model.GetParametersRequest;
import software.amazon.awssdk.services.ssm.model.GetParametersResponse;

/**
 * Owner-only Angel One connection authenticated by the PAPER EC2 instance
 * from the four existing SSM SecureStrings. The login, profile verification,
 * and WebSocket V2 all originate from the registered EC2 Elastic IP.
 *
 * This service never returns, logs, persists or distributes broker secrets.
 * Only the verified owner-gated Controller can call connect(); neither a
 * browser nor the private Lambda receives these credentials.
 */
@Service
public final class OwnerAngelOneSsmService {
    private static final String PREFIX = "/minttick/dev/angelone/";
    private static final String API_KEY = PREFIX + "api-key";
    private static final String CLIENT_CODE = PREFIX + "client-code";
    private static final String PIN = PREFIX + "login-pin";
    private static final String TOTP_SECRET = PREFIX + "totp-secret";
    private static final List<String> NAMES = List.of(API_KEY, CLIENT_CODE, PIN, TOTP_SECRET);

    private final CustomerAngelOneSessionService sessions;
    private final boolean enabled;
    private final SsmClient suppliedClient;
    private final Clock clock;

    @Autowired
    public OwnerAngelOneSsmService(CustomerAngelOneSessionService sessions,
            @Value("${MINTTICK_ANGEL_ONE_SSM_STREAM_ENABLED:false}") boolean enabled) {
        this(sessions, enabled, null, Clock.systemUTC());
    }

    OwnerAngelOneSsmService(CustomerAngelOneSessionService sessions, boolean enabled,
            SsmClient suppliedClient, Clock clock) {
        this.sessions = sessions;
        this.enabled = enabled;
        this.suppliedClient = suppliedClient;
        this.clock = clock;
    }

    public boolean available() {
        // Exposes only deployment configuration, never actual parameter values.
        return enabled;
    }

    public CustomerAngelOneSessionService.ConnectionStatus connect(UUID owner) {
        if (!enabled || owner == null) {
            throw unavailable("OWNER_SSM_STREAM_DISABLED");
        }

        final GetParametersResponse response;
        try {
            if (suppliedClient != null) {
                response = read(suppliedClient);
            } else {
                // Default instance-role provider chain; never configure static
                // AWS credentials, cross-account access or a public endpoint.
                try (SsmClient client = SsmClient.builder()
                        .region(Region.AP_SOUTH_1)
                        .overrideConfiguration(c -> c.apiCallTimeout(Duration.ofSeconds(12)))
                        .build()) {
                    response = read(client);
                }
            }
        } catch (SdkException | RuntimeException ex) {
            // Never expose AWS errors, parameter contents or credential material.
            throw unavailable("OWNER_SSM_ACCESS_UNAVAILABLE");
        }

        if (response == null || response.parameters().size() != NAMES.size()
                || (response.invalidParameters() != null && !response.invalidParameters().isEmpty())) {
            throw unavailable("OWNER_SSM_PARAMETERS_INCOMPLETE");
        }
        Map<String, String> values = new HashMap<>();
        for (var parameter : response.parameters()) {
            if (parameter == null || !NAMES.contains(parameter.name())
                    || parameter.value() == null || parameter.value().isBlank()
                    || values.putIfAbsent(parameter.name(), parameter.value()) != null) {
                throw unavailable("OWNER_SSM_PARAMETERS_INVALID");
            }
        }
        if (values.size() != NAMES.size()) {
            throw unavailable("OWNER_SSM_PARAMETERS_INCOMPLETE");
        }

        final String totp;
        try {
            totp = AngelOneTotp.generate(values.get(TOTP_SECRET), clock.instant());
        } catch (IllegalArgumentException ex) {
            throw unavailable("OWNER_SSM_TOTP_INVALID");
        }
        // Broker profile identity is verified by CustomerAngelOneSessionService.
        // The resulting short-lived JWT and feed token remain backend-memory-only.
        return sessions.connect(owner, values.get(API_KEY), values.get(CLIENT_CODE),
                values.get(PIN), totp);
    }

    private GetParametersResponse read(SsmClient client) {
        return client.getParameters(GetParametersRequest.builder()
                .names(NAMES).withDecryption(true).build());
    }

    private static AngelOneException unavailable(String code) {
        return new AngelOneException(AngelOneException.Kind.NOT_CONFIGURED, code,
                "Owner's secure Angel One connection is unavailable");
    }
}
