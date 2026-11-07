package in.aitradingsystem.provider.upstox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import in.aitradingsystem.provider.upstox.UpstoxApiClient.UpstoxProviderException;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class CustomerUpstoxOAuthServiceTest {
    private final UpstoxProperties properties = new UpstoxProperties(
        "platform-app-id", "server-only-secret",
        "https://account.example.test/api/customer/brokers/upstox/callback");
    private final UpstoxApiClient api = mock(UpstoxApiClient.class);
    private final ObjectMapper mapper = new ObjectMapper();

    private CustomerUpstoxOAuthService service() {
        return new CustomerUpstoxOAuthService(properties, api,
            Clock.fixed(Instant.parse("2026-11-07T08:00:00Z"), ZoneOffset.UTC));
    }

    private static String state(String authorizationUrl) {
        String query = URI.create(authorizationUrl).getRawQuery();
        return Arrays.stream(query.split("&"))
            .map(item -> item.split("=", 2))
            .filter(parts -> "state".equals(parts[0]))
            .map(parts -> URLDecoder.decode(parts[1], StandardCharsets.UTF_8))
            .findFirst().orElseThrow();
    }

    @Test
    void oauthCompletesOnlyForStartingCustomerAndNeverExposesBrokerToken() throws Exception {
        UUID alice = UUID.randomUUID(), bob = UUID.randomUUID();
        var service = service();
        var start = service.start(alice);
        assertThat(start.authorizationUrl()).startsWith(
            "https://api.upstox.com/v2/login/authorization/dialog?");
        assertThat(start.authorizationUrl()).contains("response_type=code");
        assertThat(start.authorizationUrl()).doesNotContain("server-only-secret");
        when(api.exchangeCode(properties, "one-use-code"))
            .thenReturn(mapper.readTree("{\"access_token\":\"upstox-bearer-secret\"}"));
        when(api.profile("upstox-bearer-secret"))
            .thenReturn(mapper.readTree(
                "{\"broker\":\"UPSTOX\",\"user_id\":\"AB123456\",\"is_active\":true}"));
        when(api.funds("upstox-bearer-secret"))
            .thenReturn(mapper.readTree("{\"equity\":{\"available_margin\":1250}}"));

        service.complete(state(start.authorizationUrl()), "one-use-code");
        assertThat(service.status(alice).connected()).isTrue();
        assertThat(service.status(alice).orderExecutionEnabled()).isFalse();
        assertThat(service.status(alice).accountMasked()).isEqualTo("***456");
        assertThat(service.status(alice).toString()).doesNotContain("upstox-bearer-secret");
        assertThat(service.status(bob).connected()).isFalse();
        assertThatThrownBy(() -> service.funds(bob)).isInstanceOf(UpstoxProviderException.class);
        assertThat(service.funds(alice).data().path("equity").path("available_margin").asInt())
            .isEqualTo(1250);
        assertThat(service.status(alice).expiresAt())
            .isEqualTo(Instant.parse("2026-11-07T22:00:00Z"));

        // Replayed callback is always rejected; it cannot reset a session.
        assertThatThrownBy(() -> service.complete(state(start.authorizationUrl()), "one-use-code"))
            .isInstanceOf(UpstoxProviderException.class);
        verify(api, times(1)).exchangeCode(properties, "one-use-code");

        service.disconnect(alice);
        assertThat(service.status(alice).connected()).isFalse();
    }

    @Test
    void invalidStateCannotExchangeTokenAndDeclinedProfileCannotConnect() throws Exception {
        var service = service();
        UUID customer = UUID.randomUUID();
        assertThatThrownBy(() -> service.complete("tampered", "code"))
            .isInstanceOf(UpstoxProviderException.class);
        verifyNoInteractions(api);
        var start = service.start(customer);
        when(api.exchangeCode(properties, "code"))
            .thenReturn(mapper.readTree("{\"access_token\":\"jwt\"}"));
        when(api.profile("jwt"))
            .thenReturn(mapper.readTree(
                "{\"broker\":\"UPSTOX\",\"user_id\":\"A12345\",\"is_active\":false}"));
        assertThatThrownBy(() -> service.complete(state(start.authorizationUrl()), "code"))
            .isInstanceOf(UpstoxProviderException.class);
        assertThat(service.status(customer).connected()).isFalse();
    }

    @Test
    void unconfiguredPlatformCannotStartOAuthAndInvalidCallbackIsRejected() {
        var missing = new UpstoxProperties("", "", "");
        var service = new CustomerUpstoxOAuthService(missing, api,
            Clock.fixed(Instant.parse("2026-11-07T08:00:00Z"), ZoneOffset.UTC));
        assertThat(service.status(UUID.randomUUID()).available()).isFalse();
        assertThatThrownBy(() -> service.start(UUID.randomUUID()))
            .isInstanceOf(UpstoxProviderException.class);
        assertThatThrownBy(() -> new UpstoxProperties("app", "secret",
            "http://localhost:8080/api/customer/brokers/upstox/callback"))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void newOAuthAttemptInvalidatesAnOlderPendingState() {
        UUID customer = UUID.randomUUID();
        var service = service();
        String older = state(service.start(customer).authorizationUrl());
        String newer = state(service.start(customer).authorizationUrl());
        assertThat(older).isNotEqualTo(newer);
        assertThatThrownBy(() -> service.complete(older, "code"))
            .isInstanceOf(UpstoxProviderException.class);
        assertThat(service.status(customer).connected()).isFalse();
        verifyNoInteractions(api);
    }
}
