package in.aitradingsystem.tradingrooms;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.server.ResponseStatusException;

/** Customer-only no-store V3 coverage status never performs an order action. */
class CustomerFnoUniverseCoverageControllerTest {
    private final FnoUniverseCoverageService service =
        mock(FnoUniverseCoverageService.class);
    private final CustomerFnoUniverseCoverageController controller =
        new CustomerFnoUniverseCoverageController(service);

    private static JwtAuthenticationToken customer() {
        var token = mock(JwtAuthenticationToken.class);
        when(token.getAuthorities()).thenReturn(
            List.of(new SimpleGrantedAuthority("ROLE_CUSTOMER")));
        return token;
    }

    @Test
    void customerReceivesFreshnessSummaryWithoutExposingPrivateKeysOrOrders() {
        var now = Instant.parse("2026-11-23T08:10:00Z");
        var receipt = new FnoUniverseCoverageService.Summary(
            "LOADED", "source-master-sha", 45451, "CONNECTED",
            256, 38, 31, 218, 37, 36, 29, now, false);
        when(service.summary()).thenReturn(receipt);
        var response = controller.summary(customer());
        assertThat(response.getBody()).isEqualTo(receipt);
        assertThat(response.getBody().liveOrdersAuthorized()).isFalse();
        assertThat(response.getHeaders().getCacheControl()).contains("no-store");
        verify(service, times(1)).summary();
    }

    @Test
    void anonymousAndNonCustomerCannotObtainRealtimeTelemetry() {
        assertThatThrownBy(() -> controller.summary(null))
            .isInstanceOf(ResponseStatusException.class)
            .satisfies(e -> assertThat(
                ((ResponseStatusException)e).getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED));
        var operator = mock(JwtAuthenticationToken.class);
        when(operator.getAuthorities()).thenReturn(
            List.of(new SimpleGrantedAuthority("ROLE_OPERATOR")));
        assertThatThrownBy(() -> controller.summary(operator))
            .isInstanceOf(ResponseStatusException.class);
        verifyNoInteractions(service);
    }
}
