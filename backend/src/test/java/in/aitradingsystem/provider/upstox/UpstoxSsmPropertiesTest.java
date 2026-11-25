package in.aitradingsystem.provider.upstox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.ssm.SsmClient;
import software.amazon.awssdk.services.ssm.model.GetParametersRequest;
import software.amazon.awssdk.services.ssm.model.GetParametersResponse;
import software.amazon.awssdk.services.ssm.model.Parameter;
import software.amazon.awssdk.services.ssm.model.ParameterType;

class UpstoxSsmPropertiesTest {
    private static final String PREFIX = "/minttick/upstox/";
    private static final String CALLBACK =
        "https://api.minttick.com/api/customer/brokers/upstox/callback";

    private static Parameter secret(String suffix, String value) {
        return Parameter.builder().name(PREFIX + suffix)
            .type(ParameterType.SECURE_STRING).value(value).build();
    }

    private static Parameter callback(String value) {
        return Parameter.builder().name(PREFIX + "redirect-uri")
            .type(ParameterType.STRING).value(value).build();
    }

    @Test
    void loadsOnlyThreeAllowlistedParametersWithDecryption() {
        SsmClient client = mock(SsmClient.class);
        when(client.getParameters(any(GetParametersRequest.class)))
            .thenReturn(GetParametersResponse.builder().parameters(
                callback(CALLBACK), secret("client-id", "developer-key"),
                secret("client-secret", "private-secret")).build());

        UpstoxProperties props = new UpstoxProperties(
            "should-not-be-used", "should-not-be-used", CALLBACK, true, client);
        assertThat(props.configured()).isTrue();
        assertThat(props.clientId()).isEqualTo("developer-key");
        assertThat(props.redirectUri()).isEqualTo(CALLBACK);
        assertThat(props.toString()).doesNotContain("private-secret")
            .doesNotContain("developer-key");
        var captor = org.mockito.ArgumentCaptor.forClass(GetParametersRequest.class);
        verify(client).getParameters(captor.capture());
        assertThat(captor.getValue().withDecryption()).isTrue();
        assertThat(captor.getValue().names()).containsExactlyInAnyOrder(
            PREFIX + "client-id", PREFIX + "client-secret", PREFIX + "redirect-uri");
    }

    @Test
    void rejectsPlaintextSecretAndNeverFallsBackToEnvironment() {
        SsmClient client = mock(SsmClient.class);
        when(client.getParameters(any(GetParametersRequest.class)))
            .thenReturn(GetParametersResponse.builder().parameters(
                callback(CALLBACK), secret("client-id", "developer-key"),
                Parameter.builder().name(PREFIX + "client-secret")
                    .type(ParameterType.STRING).value("unsafe-secret").build()).build());
        UpstoxProperties props = new UpstoxProperties(
            "fallback", "fallback-secret", CALLBACK, true, client);
        assertThat(props.configured()).isFalse();
        assertThat(props.toString()).doesNotContain("unsafe-secret");
    }

    @Test
    void missingOrInvalidCallbackFailsClosedWithoutBreakingStartup() {
        SsmClient client = mock(SsmClient.class);
        when(client.getParameters(any(GetParametersRequest.class)))
            .thenReturn(GetParametersResponse.builder().parameters(
                callback("http://localhost/api/customer/brokers/upstox/callback"),
                secret("client-id", "developer-key"),
                secret("client-secret", "private-secret")).build());
        assertThat(new UpstoxProperties("", "", "", true, client).configured()).isFalse();
        when(client.getParameters(any(GetParametersRequest.class)))
            .thenThrow(new IllegalStateException("provider error - must not appear to caller"));
        assertThat(new UpstoxProperties("", "", "", true, client).configured()).isFalse();
    }

    @Test
    void disabledSsmKeepsOriginalEnvironmentConfiguration() {
        SsmClient client = mock(SsmClient.class);
        UpstoxProperties props = new UpstoxProperties(
            "env-key", "env-secret", CALLBACK, false, client);
        assertThat(props.configured()).isTrue();
        assertThat(props.clientId()).isEqualTo("env-key");
        verifyNoInteractions(client);
    }
}
