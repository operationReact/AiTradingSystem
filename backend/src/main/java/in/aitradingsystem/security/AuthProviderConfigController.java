package in.aitradingsystem.security;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Public, non-secret authentication capability metadata used by Web/Android/iOS
 * before a session exists. Provider credentials and tokens are never returned.
 */
@RestController
@RequestMapping("/api/auth")
public class AuthProviderConfigController {
    private final boolean enabled;
    private final String providerName;
    private final String authorizationStartPath;
    private final boolean mfaReady;

    public AuthProviderConfigController(
            @Value("${app.security.oidc.enabled:false}") boolean enabled,
            @Value("${app.security.oidc.provider-name:Approved identity provider}") String providerName,
            @Value("${app.security.oidc.authorization-start-path:/api/auth/oidc/start}") String authorizationStartPath,
            @Value("${app.security.oidc.mfa-ready:true}") boolean mfaReady) {
        this.enabled = enabled;
        this.providerName = requireDisplayValue(providerName, "app.security.oidc.provider-name");
        this.authorizationStartPath = requireLocalPath(authorizationStartPath);
        this.mfaReady = mfaReady;
    }

    @GetMapping("/config")
    public AuthProviderView config() {
        return new AuthProviderView(
            enabled,
            "OIDC",
            providerName,
            enabled ? authorizationStartPath : null,
            mfaReady,
            enabled ? "AVAILABLE" : "NOT_CONFIGURED");
    }

    private static String requireDisplayValue(String value, String property) {
        if (value == null || value.isBlank() || value.length() > 120) {
            throw new IllegalArgumentException(property + " must be 1..120 characters");
        }
        return value.trim();
    }

    private static String requireLocalPath(String value) {
        if (value == null || !value.startsWith("/api/auth/") || value.contains("://") || value.length() > 240) {
            throw new IllegalArgumentException(
                "app.security.oidc.authorization-start-path must be a local /api/auth/... path");
        }
        return value;
    }

    public record AuthProviderView(
        boolean enabled,
        String protocol,
        String providerName,
        String authorizationStartPath,
        boolean mfaReady,
        String status
    ) {}
}
