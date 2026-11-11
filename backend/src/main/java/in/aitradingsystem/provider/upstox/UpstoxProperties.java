package in.aitradingsystem.provider.upstox;

import java.net.URI;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.ssm.SsmClient;
import software.amazon.awssdk.services.ssm.model.GetParametersRequest;
import software.amazon.awssdk.services.ssm.model.GetParametersResponse;
import software.amazon.awssdk.services.ssm.model.ParameterType;

/**
 * Platform-owned OAuth credentials. Never expose the secret to a client, log,
 * callback, test artifact, GitHub Action or checked-in runtime configuration.
 *
 * <p>When explicitly enabled, load exactly three developer-app parameters from
 * SSM on backend startup using its own instance-role credentials. All provider
 * errors fail closed to "not configured", without breaking PAPER readiness or
 * touching the independent Upstox Analytics token.
 */
@Component
public final class UpstoxProperties {
    public static final String CALLBACK_PATH = "/api/customer/brokers/upstox/callback";
    private static final String PREFIX = "/minttick/upstox/";
    private static final String CLIENT_ID = PREFIX + "client-id";
    private static final String CLIENT_SECRET = PREFIX + "client-secret";
    private static final String REDIRECT_URI = PREFIX + "redirect-uri";
    private static final List<String> NAMES = List.of(CLIENT_ID, CLIENT_SECRET, REDIRECT_URI);
    private static final String[] EMPTY = {"", "", ""};

    private final String clientId;
    private final String clientSecret;
    private final String redirectUri;

    @Autowired
    public UpstoxProperties(
            @Value("${app.providers.upstox.client-id:}") String clientId,
            @Value("${app.providers.upstox.client-secret:}") String clientSecret,
            @Value("${app.providers.upstox.redirect-uri:}") String redirectUri,
            @Value("${MINTTICK_UPSTOX_OAUTH_SSM_ENABLED:false}") boolean ssmEnabled) {
        this(clientId, clientSecret, redirectUri, ssmEnabled, null);
    }

    /** Existing explicit environment-value constructor remains testable. */
    public UpstoxProperties(String clientId, String clientSecret, String redirectUri) {
        this.clientId = clientId == null ? "" : clientId.trim();
        this.clientSecret = clientSecret == null ? "" : clientSecret;
        this.redirectUri = redirectUri == null ? "" : redirectUri.trim();
        if (!this.redirectUri.isEmpty()) {
            URI uri;
            try {
                uri = URI.create(this.redirectUri);
            } catch (IllegalArgumentException ex) {
                throw new IllegalArgumentException("Invalid Upstox callback URI", ex);
            }
            if (!"https".equalsIgnoreCase(uri.getScheme())
                    || uri.getHost() == null || uri.getPort() != -1
                    || !CALLBACK_PATH.equals(uri.getRawPath())
                    || uri.getRawQuery() != null || uri.getRawFragment() != null
                    || uri.getRawUserInfo() != null) {
                throw new IllegalArgumentException(
                    "Upstox OAuth callback must be an exact public HTTPS URL with the expected path");
            }
        }
    }

    // Supplied client allows strict, no-network unit tests of SSM validation.
    UpstoxProperties(String id, String secret, String uri, boolean useSsm,
                     SsmClient suppliedClient) {
        this(resolve(id, secret, uri, useSsm, suppliedClient));
    }

    private UpstoxProperties(String[] resolved) {
        this(resolved[0], resolved[1], resolved[2]);
    }

    private static String[] resolve(String id, String secret, String uri,
                                    boolean useSsm, SsmClient suppliedClient) {
        if (!useSsm) return new String[]{id, secret, uri};
        try {
            GetParametersResponse response;
            if (suppliedClient != null) {
                response = read(suppliedClient);
            } else {
                // No static AWS keys, no parameter-name input from a browser.
                try (SsmClient client = SsmClient.builder()
                        .region(Region.AP_SOUTH_1)
                        .overrideConfiguration(c -> c.apiCallTimeout(Duration.ofSeconds(12)))
                        .build()) {
                    response = read(client);
                }
            }
            if (response == null || response.parameters().size() != 3
                    || !response.invalidParameters().isEmpty()) return EMPTY.clone();
            Map<String, String> values = new HashMap<>();
            for (var parameter : response.parameters()) {
                if (parameter == null || !NAMES.contains(parameter.name())
                        || parameter.value() == null || parameter.value().isBlank()
                        || (parameter.name().equals(REDIRECT_URI)
                            ? parameter.type() != ParameterType.STRING
                                && parameter.type() != ParameterType.SECURE_STRING
                            : parameter.type() != ParameterType.SECURE_STRING)
                        || values.putIfAbsent(parameter.name(), parameter.value()) != null) {
                    return EMPTY.clone();
                }
            }
            if (values.size() != NAMES.size()) return EMPTY.clone();
            String[] result = {
                values.get(CLIENT_ID), values.get(CLIENT_SECRET), values.get(REDIRECT_URI)
            };
            // Bad operator values must not crash the account service on startup.
            return new UpstoxProperties(result).configured() ? result : EMPTY.clone();
        } catch (RuntimeException ex) {
            // Never log an AWS exception: its message may include sensitive context.
            return EMPTY.clone();
        }
    }

    private static GetParametersResponse read(SsmClient client) {
        return client.getParameters(GetParametersRequest.builder()
            .names(NAMES).withDecryption(true).build());
    }

    public boolean configured() {
        return !clientId.isBlank() && !clientSecret.isBlank() && !redirectUri.isBlank();
    }

    public String clientId() { return clientId; }
    public String clientSecret() { return clientSecret; }
    public String redirectUri() { return redirectUri; }

    @Override
    public String toString() {
        return "UpstoxProperties[credentials redacted, configured=" + configured() + "]";
    }
}
