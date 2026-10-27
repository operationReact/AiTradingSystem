package in.aitradingsystem.config;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Angel One SmartAPI settings. Credentials come only from environment variables; they are never logged or exposed.
 */
@Validated
@ConfigurationProperties(prefix = "app.providers.angel-one")
public record AngelOneProperties(
        String apiKey,
        String clientCode,
        String password,
        String totpSecret,
        @NotBlank String baseUrl,
        @Min(100) @Max(60_000) int connectTimeoutMs,
        @Min(100) @Max(60_000) int readTimeoutMs,
        @Min(60) @Max(86_400) long sessionMaxAgeSeconds,
        @Min(0) @Max(60_000) int loginMinIntervalMs,
        @Min(0) @Max(60_000) int quoteMinIntervalMs) {

    public boolean credentialsConfigured() {
        return notBlank(apiKey) && notBlank(clientCode) && notBlank(password) && notBlank(totpSecret);
    }

    /** The generated record toString would print the password, API key and TOTP secret. */
    @Override
    public String toString() {
        return "AngelOneProperties[redacted, baseUrl=" + baseUrl + "]";
    }

    private static boolean notBlank(String value) {
        return value != null && !value.isBlank();
    }
}
