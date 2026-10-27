package in.aitradingsystem.config;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import java.net.URI;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.ConstructorBinding;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties(prefix = "app.providers.angel-one")
public record AngelOneProperties(
        String apiKey, String clientCode, String password, String totpSecret,
        @NotBlank String baseUrl,
        @Min(100) @Max(60_000) int connectTimeoutMs,
        @Min(100) @Max(60_000) int readTimeoutMs,
        @Min(60) @Max(86_400) long sessionMaxAgeSeconds,
        @Min(0) @Max(60_000) int loginMinIntervalMs,
        @Min(0) @Max(60_000) int quoteMinIntervalMs,
        @Min(0) @Max(10_000) int sessionRetryMaxWaitMs) {

    public AngelOneProperties(String apiKey, String clientCode, String password, String totpSecret,
            String baseUrl, int connectTimeoutMs, int readTimeoutMs, long sessionMaxAgeSeconds,
            int loginMinIntervalMs, int quoteMinIntervalMs) {
        this(apiKey, clientCode, password, totpSecret, baseUrl, connectTimeoutMs, readTimeoutMs,
                sessionMaxAgeSeconds, loginMinIntervalMs, quoteMinIntervalMs, 5_000);
    }

    @ConstructorBinding
    public AngelOneProperties {
        if (baseUrl != null && !baseUrl.isBlank()) {
            try {
                URI uri = URI.create(baseUrl);
                boolean valid = "https".equalsIgnoreCase(uri.getScheme()) && uri.getHost() != null
                        && uri.getRawUserInfo() == null && uri.getRawQuery() == null
                        && uri.getRawFragment() == null
                        && (uri.getPath() == null || uri.getPath().isEmpty() || "/".equals(uri.getPath()));
                if (!valid) {
                    throw new IllegalArgumentException(
                            "Angel One base URL must be an HTTPS origin without credentials, path, query, or fragment");
                }
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException("Angel One base URL must be a valid HTTPS origin", e);
            }
        }
    }

    public boolean credentialsConfigured() {
        return notBlank(apiKey) && notBlank(clientCode) && notBlank(password) && notBlank(totpSecret);
    }

    @Override
    public String toString() {
        return "AngelOneProperties[redacted, baseUrl=" + baseUrl + "]";
    }

    private static boolean notBlank(String value) {
        return value != null && !value.isBlank();
    }
}
