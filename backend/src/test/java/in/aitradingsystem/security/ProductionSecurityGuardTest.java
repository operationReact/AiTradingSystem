package in.aitradingsystem.security;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

class ProductionSecurityGuardTest {

    @Test
    void productionRejectsLegacyApiKey() {
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles("production");

        assertThatThrownBy(() -> guard(environment, "legacy-key", false, true, List.of("https://maarglobal.com")).afterPropertiesSet())
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("static API-key");
    }

    @Test
    void productionRejectsDevBootstrapInsecureCookieAndHttpCors() {
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles("prod");

        assertThatThrownBy(() -> guard(environment, "", true, true, List.of("https://maarglobal.com")).afterPropertiesSet())
            .hasMessageContaining("dev session bootstrap");
        assertThatThrownBy(() -> guard(environment, "", false, false, List.of("https://maarglobal.com")).afterPropertiesSet())
            .hasMessageContaining("cookies must be Secure");
        assertThatThrownBy(() -> guard(environment, "", false, true, List.of("http://maarglobal.com")).afterPropertiesSet())
            .hasMessageContaining("must use HTTPS");
    }

    @Test
    void productionAcceptsSessionOnlySecureConfiguration() {
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles("production");

        assertThatCode(() -> guard(environment, "", false, true, List.of("https://maarglobal.com")).afterPropertiesSet())
            .doesNotThrowAnyException();
    }

    @Test
    void localDevelopmentMayRetainTemporaryCompatibilityBridge() {
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles("dev");

        assertThatCode(() -> guard(environment, "local-key", true, false, List.of("http://localhost:5173")).afterPropertiesSet())
            .doesNotThrowAnyException();
    }

    private ProductionSecurityGuard guard(
            MockEnvironment environment,
            String apiKey,
            boolean devBootstrap,
            boolean secureCookie,
            List<String> origins) {
        return new ProductionSecurityGuard(environment, apiKey, devBootstrap, secureCookie, origins);
    }
}
