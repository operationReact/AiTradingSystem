package in.aitradingsystem.provider.upstox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;

import in.aitradingsystem.provider.upstox.UpstoxApiClient.UpstoxProviderException;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

class CustomerUpstoxControllerTest {
    private final CustomerUpstoxOAuthService oauth = mock(CustomerUpstoxOAuthService.class);
    private final CustomerUpstoxController controller =
        new CustomerUpstoxController(null, oauth);

    @Test
    void callbackReportsSafeStateInvalidFailureWithoutReflectingInput() {
        doThrow(new UpstoxProviderException("UPSTOX_OAUTH_STATE_INVALID"))
            .when(oauth).complete("bad-state", "sensitive-one-time-code");
        var result = controller.callback("bad-state", "sensitive-one-time-code", null);
        assertThat(result.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(result.getHeaders().getFirst("X-MintTick-Upstox-Outcome"))
            .isEqualTo("STATE_INVALID");
        assertThat(result.getBody()).doesNotContain("bad-state")
            .doesNotContain("sensitive-one-time-code");
        assertThat(result.getHeaders().getFirst("Referrer-Policy"))
            .isEqualTo("no-referrer");
    }

    @Test
    void callbackOnlyReportsWhitelistedProviderFailure() {
        doThrow(new UpstoxProviderException("broker-injected-unsafe-message"))
            .when(oauth).complete("safe-state", "sensitive-code");
        var result = controller.callback("safe-state", "sensitive-code", null);
        assertThat(result.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(result.getHeaders().getFirst("X-MintTick-Upstox-Outcome"))
            .isEqualTo("UNVERIFIED");
        assertThat(result.getBody()).doesNotContain("broker-injected-unsafe-message");
    }

    @Test
    void successfulCallbackReportsConnectedWithoutDisclosingTheCode() {
        var result = controller.callback("safe-state", "sensitive-code", null);
        assertThat(result.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(result.getHeaders().getFirst("X-MintTick-Upstox-Outcome"))
            .isEqualTo("CONNECTED");
        assertThat(result.getBody()).doesNotContain("sensitive-code");
    }

    @Test
    void declinedCallbackReportsSafeDeclinedOutcome() {
        doThrow(new UpstoxProviderException("UPSTOX_AUTH_DECLINED"))
            .when(oauth).complete("valid-state", null);
        var result = controller.callback("valid-state", null, "broker-secret-description");
        assertThat(result.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(result.getHeaders().getFirst("X-MintTick-Upstox-Outcome"))
            .isEqualTo("DECLINED");
        assertThat(result.getBody()).doesNotContain("broker-secret-description");
    }
}
