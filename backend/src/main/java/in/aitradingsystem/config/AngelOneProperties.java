package in.aitradingsystem.config;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import java.net.URI;
import java.util.Set;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.ConstructorBinding;
import org.springframework.validation.annotation.Validated;

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
        @Min(0) @Max(60_000) int quoteMinIntervalMs,
        @Min(0) @Max(10_000) int sessionRetryMaxWaitMs,
        @NotBlank String instrumentMasterUrl,
        @Min(1_048_576) @Max(104_857_600) int instrumentMasterMaxBytes,
        @Min(100) @Max(2_000_000) int instrumentMasterMaxRows,
        @Min(1) @Max(100_000) int instrumentSearchMaxPageSize,
        @Min(0) @Max(20) int instrumentMasterMaxRejectedPercent,
        @Min(1) @Max(100) int instrumentMasterMinRetainedPercent) {

    private static final Set<String> MASTER_HOSTS =
            Set.of("margincalculator.angelone.in", "margincalculator.angelbroking.com");
    private static final String MASTER_PATH = "/OpenAPI_File/files/OpenAPIScripMaster.json";

    /** Backwards-compatible constructor for existing tests and callers. */
    public AngelOneProperties(String apiKey, String clientCode, String password, String totpSecret,
            String baseUrl, int connectTimeoutMs, int readTimeoutMs, long sessionMaxAgeSeconds,
            int loginMinIntervalMs, int quoteMinIntervalMs, int sessionRetryMaxWaitMs) {
        this(apiKey, clientCode, password, totpSecret, baseUrl, connectTimeoutMs, readTimeoutMs,
                sessionMaxAgeSeconds, loginMinIntervalMs, quoteMinIntervalMs, sessionRetryMaxWaitMs,
                "https://margincalculator.angelone.in/OpenAPI_File/files/OpenAPIScripMaster.json",
                52_428_800, 500_000, 100, 5, 80);
    }

    public AngelOneProperties(String apiKey, String clientCode, String password, String totpSecret,
            String baseUrl, int connectTimeoutMs, int readTimeoutMs, long sessionMaxAgeSeconds,
            int loginMinIntervalMs, int quoteMinIntervalMs) {
        this(apiKey, clientCode, password, totpSecret, baseUrl, connectTimeoutMs, readTimeoutMs,
                sessionMaxAgeSeconds, loginMinIntervalMs, quoteMinIntervalMs, 5_000);
    }

    @ConstructorBinding
    public AngelOneProperties {
        validateHttpsOrigin(baseUrl);
        validateMasterUrl(instrumentMasterUrl);
        if (instrumentMasterMaxRejectedPercent < 0 || instrumentMasterMaxRejectedPercent > 20) {
            throw new IllegalArgumentException("instrument master rejected-percent limit must be between 0 and 20");
        }
    }

    public boolean credentialsConfigured() {
        return notBlank(apiKey) && notBlank(clientCode) && notBlank(password) && notBlank(totpSecret);
    }

    @Override
    public String toString() {
        return "AngelOneProperties[redacted, baseUrl=" + baseUrl + ", instrumentMasterUrl=" + instrumentMasterUrl + "]";
    }

    private static void validateHttpsOrigin(String value) {
        if (value == null || value.isBlank()) return;
        try {
            URI uri = URI.create(value);
            boolean valid = "https".equalsIgnoreCase(uri.getScheme()) && uri.getHost() != null
                    && uri.getRawUserInfo() == null && uri.getRawQuery() == null && uri.getRawFragment() == null
                    && (uri.getPath() == null || uri.getPath().isEmpty() || "/".equals(uri.getPath()));
            if (!valid) throw new IllegalArgumentException("Angel One base URL must be an HTTPS origin");
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Angel One base URL must be a valid HTTPS origin", e);
        }
    }

    private static void validateMasterUrl(String value) {
        if (value == null || value.isBlank()) return;
        try {
            URI uri = URI.create(value);
            boolean valid = "https".equalsIgnoreCase(uri.getScheme())
                    && MASTER_HOSTS.contains(uri.getHost() == null ? "" : uri.getHost().toLowerCase())
                    && (uri.getPort() == -1 || uri.getPort() == 443)
                    && MASTER_PATH.equals(uri.getPath())
                    && uri.getRawUserInfo() == null && uri.getRawQuery() == null && uri.getRawFragment() == null;
            if (!valid) throw new IllegalArgumentException("Instrument master URL must use Angel One's official HTTPS source");
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Instrument master URL must use Angel One's official HTTPS source", e);
        }
    }

    private static boolean notBlank(String value) {
        return value != null && !value.isBlank();
    }
}
