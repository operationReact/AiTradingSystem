package in.aitradingsystem.provider.upstox;

import java.net.URI;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Platform-owned OAuth credentials. The client secret must only be injected
 * from the deployment's secret store, never committed or sent to Flutter.
 */
@Component
public final class UpstoxProperties {
    public static final String CALLBACK_PATH = "/api/customer/brokers/upstox/callback";

    private final String clientId;
    private final String clientSecret;
    private final String redirectUri;

    public UpstoxProperties(
            @Value("${app.providers.upstox.client-id:}") String clientId,
            @Value("${app.providers.upstox.client-secret:}") String clientSecret,
            @Value("${app.providers.upstox.redirect-uri:}") String redirectUri) {
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
