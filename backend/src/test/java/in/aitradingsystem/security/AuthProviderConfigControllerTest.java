package in.aitradingsystem.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class AuthProviderConfigControllerTest {

    @Test
    void productionAuthDiscoveryFailsClosedWhenProviderIsNotConfigured() {
        AuthProviderConfigController controller = new AuthProviderConfigController(
            false, "Approved identity provider", "/api/auth/oidc/start", true);

        var view = controller.config();

        assertThat(view.enabled()).isFalse();
        assertThat(view.protocol()).isEqualTo("OIDC");
        assertThat(view.authorizationStartPath()).isNull();
        assertThat(view.status()).isEqualTo("NOT_CONFIGURED");
        assertThat(view.providerName()).isEqualTo("Approved identity provider");
    }

    @Test
    void configuredProviderExposesOnlyNonSecretLaunchMetadata() {
        AuthProviderConfigController controller = new AuthProviderConfigController(
            true, "Corporate Identity", "/api/auth/oidc/start", true);

        var view = controller.config();

        assertThat(view.enabled()).isTrue();
        assertThat(view.authorizationStartPath()).isEqualTo("/api/auth/oidc/start");
        assertThat(view.mfaReady()).isTrue();
        assertThat(view.status()).isEqualTo("AVAILABLE");
    }

    @Test
    void externalAuthorizationUrlsAreRejected() {
        assertThatThrownBy(() -> new AuthProviderConfigController(
            true, "Identity", "https://evil.example/start", true))
            .isInstanceOf(IllegalArgumentException.class);
    }
}
