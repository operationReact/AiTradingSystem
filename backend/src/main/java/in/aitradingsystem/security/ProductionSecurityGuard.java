package in.aitradingsystem.security;

import java.net.URI;
import java.util.Arrays;
import java.util.List;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

@Component
public final class ProductionSecurityGuard implements InitializingBean {
    private final Environment environment;
    private final String apiKey;
    private final boolean devBootstrapEnabled;
    private final boolean cookieSecure;
    private final List<String> allowedOrigins;

    public ProductionSecurityGuard(
            Environment environment,
            @Value("${app.security.api-key:}") String apiKey,
            @Value("${app.security.dev-session-bootstrap-enabled:false}") boolean devBootstrapEnabled,
            @Value("${app.security.session.cookie-secure:true}") boolean cookieSecure,
            @Value("${app.cors.allowed-origins}") List<String> allowedOrigins) {
        this.environment = environment;
        this.apiKey = apiKey == null ? "" : apiKey;
        this.devBootstrapEnabled = devBootstrapEnabled;
        this.cookieSecure = cookieSecure;
        this.allowedOrigins = allowedOrigins;
    }

    @Override
    public void afterPropertiesSet() {
        boolean production = Arrays.stream(environment.getActiveProfiles())
            .anyMatch(profile -> profile.equalsIgnoreCase("prod") || profile.equalsIgnoreCase("production"));
        if (!production) {
            return;
        }
        if (!apiKey.isBlank()) {
            throw new IllegalStateException("Production must not enable legacy static API-key authentication");
        }
        if (devBootstrapEnabled) {
            throw new IllegalStateException("Production must not enable the dev session bootstrap endpoint");
        }
        if (!cookieSecure) {
            throw new IllegalStateException("Production session cookies must be Secure");
        }
        for (String configured : allowedOrigins) {
            String origin = configured == null ? "" : configured.trim();
            URI uri = URI.create(origin);
            if (!"https".equalsIgnoreCase(uri.getScheme())) {
                throw new IllegalStateException("Production CORS origins must use HTTPS");
            }
        }
    }
}
