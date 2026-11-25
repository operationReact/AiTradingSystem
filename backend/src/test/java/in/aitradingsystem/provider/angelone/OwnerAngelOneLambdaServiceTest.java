package in.aitradingsystem.provider.angelone;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import software.amazon.awssdk.core.SdkBytes;
import software.amazon.awssdk.services.lambda.LambdaClient;
import software.amazon.awssdk.services.lambda.model.InvokeRequest;
import software.amazon.awssdk.services.lambda.model.InvokeResponse;

class OwnerAngelOneLambdaServiceTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private final LambdaClient lambda = mock(LambdaClient.class);
    @SuppressWarnings("unchecked")
    private final ObjectProvider<LambdaClient> clients = mock(ObjectProvider.class);

    private OwnerAngelOneLambdaService service(String mode) {
        when(clients.getIfAvailable()).thenReturn(lambda);
        when(clients.getObject()).thenReturn(lambda);
        return new OwnerAngelOneLambdaService(clients, mapper, mode,
            "minttick-dev-angelone-feed",
            Clock.fixed(Instant.parse("2026-11-07T06:00:00Z"), ZoneOffset.UTC));
    }

    private void respond(String json) {
        when(lambda.invoke(any(InvokeRequest.class))).thenReturn(
            InvokeResponse.builder().statusCode(200)
                .payload(SdkBytes.fromUtf8String(json)).build());
    }

    @Test
    void neverTreatsSsmParametersAsAvailableWithoutPrivateLambda() {
        var bridge = service("ANGEL_ONE");
        assertThat(bridge.available()).isFalse();
        assertThatThrownBy(() -> bridge.connect(UUID.randomUUID()))
            .isInstanceOf(AngelOneException.class);
        verifyNoInteractions(lambda);
    }

    @Test
    void ownerScopedConnectionKeepsOnlyMaskedIdentityAndExpires() throws Exception {
        var bridge = service("ANGEL_ONE_LAMBDA");
        respond("{\"ok\":true,\"broker\":\"ANGEL_ONE\","
            + "\"clientCodeMasked\":\"***234\",\"orderExecutionEnabled\":false}");
        UUID owner = UUID.randomUUID(), stranger = UUID.randomUUID();
        var link = bridge.connect(owner);
        assertThat(link.connected()).isTrue();
        assertThat(link.clientCodeMasked()).isEqualTo("***234");
        assertThat(link.orderExecutionEnabled()).isFalse();
        assertThat(link.expiresAt()).isEqualTo(Instant.parse("2026-11-07T09:00:00Z"));
        assertThat(bridge.status(stranger).connected()).isFalse();
        assertThatThrownBy(() -> bridge.read(stranger,"funds"))
            .isInstanceOf(CustomerAngelOneSessionService.BrokerConnectionRequiredException.class);

        respond("{\"ok\":true,\"provider\":\"ANGEL_ONE\","
            + "\"data\":{\"availablecash\":\"5000.00\"}}");
        var funds=bridge.read(owner,"funds");
        assertThat(funds.data().path("availablecash").asText()).isEqualTo("5000.00");
        verify(lambda, times(2)).invoke(any(InvokeRequest.class));
        bridge.disconnect(owner);
        assertThat(bridge.status(owner).connected()).isFalse();
    }

    @Test
    void noOrderMutationOrUnverifiedBrokerIdentityCanBeAccepted() {
        var bridge = service("ANGEL_ONE_LAMBDA");
        respond("{\"ok\":true,\"broker\":\"ANGEL_ONE\","
            + "\"clientCodeMasked\":\"***234\",\"orderExecutionEnabled\":true}");
        assertThatThrownBy(() -> bridge.connect(UUID.randomUUID()))
            .isInstanceOf(AngelOneException.class);
        assertThat(bridge.status(UUID.randomUUID()).connected()).isFalse();
    }
}
