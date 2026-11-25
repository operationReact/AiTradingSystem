package in.aitradingsystem.provider.angelone;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import software.amazon.awssdk.services.ssm.SsmClient;
import software.amazon.awssdk.services.ssm.model.GetParametersRequest;
import software.amazon.awssdk.services.ssm.model.GetParametersResponse;
import software.amazon.awssdk.services.ssm.model.Parameter;
import software.amazon.awssdk.services.ssm.model.ParameterType;

class OwnerAngelOneSsmServiceTest {
    private static final String ROOT = "/minttick/dev/angelone/";
    private final SsmClient ssm = mock(SsmClient.class);
    private final CustomerAngelOneSessionService broker = mock(CustomerAngelOneSessionService.class);
    private final Clock clock = Clock.fixed(Instant.parse("2026-11-08T08:30:00Z"), ZoneOffset.UTC);

    private static Parameter secret(String name, String value) {
        return Parameter.builder().name(ROOT + name).value(value)
                .type(ParameterType.SECURE_STRING).build();
    }

    @Test
    void ownerConnectsUsingOnlyFourDecryptedParametersAndKeepsTokensInSessionService() {
        var id = UUID.randomUUID();
        var requested = GetParametersResponse.builder()
                .parameters(
                    secret("api-key", "private-api-key"),
                    secret("client-code", "AL1234"),
                    secret("login-pin", "1234"),
                    secret("totp-secret", "JBSWY3DPEHPK3PXP"))
                .build();
        when(ssm.getParameters(any(GetParametersRequest.class))).thenReturn(requested);
        when(broker.connect(eq(id), eq("private-api-key"), eq("AL1234"),
                eq("1234"), matches("[0-9]{6}")))
            .thenReturn(new CustomerAngelOneSessionService.ConnectionStatus(
                true, "ANGEL_ONE", "***234", clock.instant().plusSeconds(3600), false));

        var service = new OwnerAngelOneSsmService(broker, true, ssm, clock);
        var connected = service.connect(id);
        assertThat(connected.connected()).isTrue();
        assertThat(connected.orderExecutionEnabled()).isFalse();

        var captor = ArgumentCaptor.forClass(GetParametersRequest.class);
        verify(ssm).getParameters(captor.capture());
        assertThat(captor.getValue().withDecryption()).isTrue();
        assertThat(captor.getValue().names()).containsExactlyInAnyOrder(
                ROOT + "api-key", ROOT + "client-code",
                ROOT + "login-pin", ROOT + "totp-secret");
        verify(broker).connect(eq(id), eq("private-api-key"), eq("AL1234"),
                eq("1234"), matches("[0-9]{6}"));
        verifyNoMoreInteractions(ssm, broker);
    }

    @Test
    void acceptsExactAllowlistedParametersWhenStoredAsPlainStrings() {
        var id = UUID.randomUUID();
        when(ssm.getParameters(any(GetParametersRequest.class))).thenReturn(
            GetParametersResponse.builder().parameters(
                Parameter.builder().name(ROOT + "api-key").value("private-api-key")
                    .type(ParameterType.STRING).build(),
                Parameter.builder().name(ROOT + "client-code").value("AL1234")
                    .type(ParameterType.STRING).build(),
                Parameter.builder().name(ROOT + "login-pin").value("1234")
                    .type(ParameterType.STRING).build(),
                Parameter.builder().name(ROOT + "totp-secret").value("JBSWY3DPEHPK3PXP")
                    .type(ParameterType.STRING).build())
                .build());
        when(broker.connect(eq(id), eq("private-api-key"), eq("AL1234"),
                eq("1234"), matches("[0-9]{6}")))
            .thenReturn(new CustomerAngelOneSessionService.ConnectionStatus(
                true, "ANGEL_ONE", "***234", clock.instant().plusSeconds(3600), false));
        var service = new OwnerAngelOneSsmService(broker, true, ssm, clock);
        assertThat(service.connect(id).connected()).isTrue();
        verify(broker).connect(eq(id), eq("private-api-key"), eq("AL1234"),
                eq("1234"), matches("[0-9]{6}"));
    }

    @Test
    void mixedStringAndSecureStringParametersAreAccepted() {
        var id = UUID.randomUUID();
        when(ssm.getParameters(any(GetParametersRequest.class))).thenReturn(
            GetParametersResponse.builder().parameters(
                Parameter.builder().name(ROOT + "api-key").value("private-api-key")
                    .type(ParameterType.STRING).build(),
                secret("client-code", "AL1234"),
                secret("login-pin", "1234"),
                secret("totp-secret", "JBSWY3DPEHPK3PXP"))
                .build());
        var service = new OwnerAngelOneSsmService(broker, true, ssm, clock);
        service.connect(id);
        verify(broker).connect(eq(id), eq("private-api-key"), eq("AL1234"),
                eq("1234"), matches("[0-9]{6}"));
    }

    @Test
    void arbitraryNonStringParameterTypeIsRejected() {
        when(ssm.getParameters(any(GetParametersRequest.class))).thenReturn(
            GetParametersResponse.builder().parameters(
                Parameter.builder().name(ROOT + "api-key").value("private-api-key")
                    .type(ParameterType.UNKNOWN_TO_SDK_VERSION).build(),
                secret("client-code", "AL1234"), secret("login-pin", "1234"),
                secret("totp-secret", "JBSWY3DPEHPK3PXP")).build());
        var service = new OwnerAngelOneSsmService(broker, true, ssm, clock);
        assertThatThrownBy(() -> service.connect(UUID.randomUUID()))
            .isInstanceOf(AngelOneException.class);
        verifyNoInteractions(broker);
    }

    @Test
    void disabledFeatureNeverReadsSecureStringsOrLogsIn() {
        var service = new OwnerAngelOneSsmService(broker, false, ssm, clock);
        assertThat(service.available()).isFalse();
        assertThatThrownBy(() -> service.connect(UUID.randomUUID()))
                .isInstanceOf(AngelOneException.class)
                .hasMessageNotContaining("private");
        verifyNoInteractions(ssm, broker);
    }

    @Test
    void missingCredentialFailsClosedBeforeCallingProviderLogin() {
        when(ssm.getParameters(any(GetParametersRequest.class))).thenReturn(
            GetParametersResponse.builder().parameters(
                secret("api-key", "private-api-key"),
                secret("client-code", "AL1234"),
                secret("login-pin", "1234"))
            .invalidParameters(ROOT + "totp-secret").build());
        var service = new OwnerAngelOneSsmService(broker, true, ssm, clock);
        assertThatThrownBy(() -> service.connect(UUID.randomUUID()))
                .isInstanceOf(AngelOneException.class);
        verifyNoInteractions(broker);
    }

    @Test
    void unexpectedParameterFailsClosedAndNeverLeaksCredentialsInErrors() {
        when(ssm.getParameters(any(GetParametersRequest.class))).thenReturn(
            GetParametersResponse.builder().parameters(
                secret("api-key", "private-api-key"),
                secret("client-code", "AL1234"),
                secret("login-pin", "1234"),
                secret("unexpected", "super-secret")).build());
        var service = new OwnerAngelOneSsmService(broker, true, ssm, clock);
        assertThatThrownBy(() -> service.connect(UUID.randomUUID()))
                .isInstanceOf(AngelOneException.class)
                .hasMessageNotContaining("super-secret");
        verifyNoInteractions(broker);
    }
}
