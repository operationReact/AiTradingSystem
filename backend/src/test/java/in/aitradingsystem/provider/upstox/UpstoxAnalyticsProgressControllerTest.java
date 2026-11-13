package in.aitradingsystem.provider.upstox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.server.ResponseStatusException;

class UpstoxAnalyticsProgressControllerTest {
    private final Clock clock =
            Clock.fixed(Instant.parse("2026-11-13T01:00:00Z"), ZoneOffset.UTC);
    private final UpstoxAnalyticsProgressRepository store =
            mock(UpstoxAnalyticsProgressRepository.class);

    @Test
    void correctOwnerReadsOnlySafeNoStoreProgressWithNoExecutionAuthority() {
        when(store.snapshot(clock.instant())).thenReturn(List.of());
        Jwt jwt = Jwt.withTokenValue("fixture").header("alg", "none")
                .claim("sub", "owner-subject").build();
        var controller = new UpstoxAnalyticsProgressController(store, "owner-subject", clock);
        var response = controller.progress(new JwtAuthenticationToken(jwt));
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getHeaders().getCacheControl()).contains("no-store");
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().contractVersion())
                .isEqualTo("minttick.upstox.analytics.progress.v1");
        assertThat(response.getBody().source()).isEqualTo("UPSTOX_ANALYTICS_READONLY");
        assertThat(response.getBody().liveTradingEnabled()).isFalse();
        assertThat(response.getBody().paperTradingEnabled()).isFalse();
        assertThat(response.getBody().modelTrainingRightsVerified()).isFalse();
        verify(store).snapshot(clock.instant());
    }

    @Test
    void refusesOtherCustomerOrMissingOwnerConfigurationWithoutReading() {
        Jwt jwt = Jwt.withTokenValue("fixture").header("alg", "none")
                .claim("sub", "other-subject").build();
        var controller = new UpstoxAnalyticsProgressController(store, "owner-subject", clock);
        assertThatThrownBy(() -> controller.progress(new JwtAuthenticationToken(jwt)))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("403");
        var unconfigured = new UpstoxAnalyticsProgressController(store, "", clock);
        assertThatThrownBy(() -> unconfigured.progress(new JwtAuthenticationToken(jwt)))
                .isInstanceOf(ResponseStatusException.class);
        verify(store, never()).snapshot(any());
    }
}
