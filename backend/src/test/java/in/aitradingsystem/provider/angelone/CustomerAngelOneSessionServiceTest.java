package in.aitradingsystem.provider.angelone;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class CustomerAngelOneSessionServiceTest {
    private final AngelOneApiClient api = mock(AngelOneApiClient.class);
    private final AngelOneRateLimiter limiter = mock(AngelOneRateLimiter.class);
    private final ObjectMapper mapper = new ObjectMapper();

    private CustomerAngelOneSessionService service(Clock clock) {
        return new CustomerAngelOneSessionService(api, limiter, clock);
    }

    @Test
    void differentCustomersAreIsolatedAndSecretsNeverAppearInStatus() throws Exception {
        var service = service(Clock.fixed(Instant.parse("2026-11-07T06:00:00Z"), ZoneOffset.UTC));
        UUID alice = UUID.randomUUID(), bob = UUID.randomUUID();
        when(api.customerLogin("key-alice", "AL1234", "1234", "123456"))
            .thenReturn(new AngelOneApiClient.LoginTokens("jwt-alice-secret", "refresh"));
        when(api.customerProfile("key-alice", "jwt-alice-secret"))
            .thenReturn(mapper.readTree("{\"clientcode\":\"AL1234\"}"));
        when(api.customerFunds("key-alice", "jwt-alice-secret"))
            .thenReturn(mapper.readTree("{\"availablecash\":\"5000.00\"}"));

        var connected = service.connect(alice, "key-alice", "AL1234", "1234", "123456");
        assertThat(connected.connected()).isTrue();
        assertThat(connected.orderExecutionEnabled()).isFalse();
        assertThat(connected.toString()).doesNotContain("jwt-", "key-", "1234");
        assertThat(service.status(bob).connected()).isFalse();
        assertThatThrownBy(() -> service.funds(bob))
            .isInstanceOf(CustomerAngelOneSessionService.BrokerConnectionRequiredException.class);
        assertThat(service.funds(alice).data().get("availablecash").asText()).isEqualTo("5000.00");
        verify(api).customerFunds("key-alice", "jwt-alice-secret");
        service.disconnect(alice);
        assertThat(service.status(alice).connected()).isFalse();
    }

    @Test
    void rejectsBrokerProfileMismatchBeforeBindingAccount() throws Exception {
        var service = service(Clock.fixed(Instant.parse("2026-11-07T06:00:00Z"), ZoneOffset.UTC));
        UUID user = UUID.randomUUID();
        when(api.customerLogin("key", "AL1234", "1234", "123456"))
            .thenReturn(new AngelOneApiClient.LoginTokens("jwt", "refresh"));
        when(api.customerProfile("key", "jwt"))
            .thenReturn(mapper.readTree("{\"clientcode\":\"OTHERUSER\"}"));
        assertThatThrownBy(() -> service.connect(user, "key", "AL1234", "1234", "123456"))
            .isInstanceOf(AngelOneException.class);
        assertThat(service.status(user).connected()).isFalse();
    }

    @Test
    void neverAcceptsTotpSeedOrInvalidPinAsConnectionInput() {
        var service = service(Clock.fixed(Instant.parse("2026-11-07T06:00:00Z"), ZoneOffset.UTC));
        assertThatThrownBy(() -> service.connect(UUID.randomUUID(), "key", "AL1234", "1234", "TOTPSEED"))
            .isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(api);
    }

    @Test
    void automaticallyDisconnectsAtBrokerMidnight() throws Exception {
        var service = service(Clock.fixed(Instant.parse("2026-11-07T18:28:00Z"), ZoneOffset.UTC));
        UUID user = UUID.randomUUID();
        when(api.customerLogin("key", "AL1234", "1234", "123456"))
            .thenReturn(new AngelOneApiClient.LoginTokens("jwt", "refresh"));
        when(api.customerProfile("key", "jwt"))
            .thenReturn(mapper.readTree("{\"clientcode\":\"AL1234\"}"));
        var connected = service.connect(user, "key", "AL1234", "1234", "123456");
        assertThat(connected.expiresAt()).isEqualTo(Instant.parse("2026-11-07T18:30:00Z"));
        assertThat(connected.orderExecutionEnabled()).isFalse();
    }

    @Test
    void invalidSessionIsRemovedAfterProviderExpiry() throws Exception {
        var service = service(Clock.fixed(Instant.parse("2026-11-07T06:00:00Z"), ZoneOffset.UTC));
        UUID user = UUID.randomUUID();
        when(api.customerLogin("key", "AL1234", "1234", "123456"))
            .thenReturn(new AngelOneApiClient.LoginTokens("jwt", "refresh"));
        when(api.customerProfile("key", "jwt"))
            .thenReturn(mapper.readTree("{\"clientcode\":\"AL1234\"}"));
        service.connect(user, "key", "AL1234", "1234", "123456");
        when(api.customerPositions("key", "jwt"))
            .thenThrow(new AngelOneException(
                AngelOneException.Kind.SESSION_EXPIRED, "AG8001", "Session expired"));
        assertThatThrownBy(() -> service.positions(user)).isInstanceOf(AngelOneException.class);
        assertThat(service.status(user).connected()).isFalse();
    }
}
