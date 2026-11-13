package in.aitradingsystem.provider.upstox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.ssm.SsmClient;
import software.amazon.awssdk.services.ssm.model.GetParametersRequest;
import software.amazon.awssdk.services.ssm.model.GetParametersResponse;
import software.amazon.awssdk.services.ssm.model.Parameter;
import software.amazon.awssdk.services.ssm.model.ParameterType;

class OwnerUpstoxTotpServiceTest {
    private static final Clock CLOCK =
        Clock.fixed(Instant.ofEpochSecond(59), ZoneOffset.UTC);
    private static final UpstoxProperties APP = new UpstoxProperties(
        "individual-app-key", "private-key",
        "https://api.minttick.com/api/customer/brokers/upstox/callback");
    private static final UUID OWNER = UUID.fromString("00000000-0000-0000-0000-000000000001");

    private static CustomerUpstoxOAuthService.Status disconnected() {
        return new CustomerUpstoxOAuthService.Status(true, false, "UPSTOX",
            null, null, false);
    }
    private static CustomerUpstoxOAuthService.Status connected() {
        return new CustomerUpstoxOAuthService.Status(true, true, "UPSTOX",
            "***123", Instant.parse("2030-01-01T00:00:00Z"), false);
    }
    private static SsmClient configuredSsm() {
        SsmClient ssm = mock(SsmClient.class);
        when(ssm.getParameters(any(GetParametersRequest.class))).thenReturn(
            GetParametersResponse.builder().parameters(
                Parameter.builder().name("/minttick/upstox/login-pin")
                    .type(ParameterType.SECURE_STRING).value("123456").build(),
                Parameter.builder().name("/minttick/upstox/totp-secret")
                    .type(ParameterType.SECURE_STRING)
                    .value("GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ").build()
            ).build());
        return ssm;
    }

    @Test
    void distinguishesOnlyKnownUpstoxTotpErrorsWithoutLeakingBrokerResponses() {
        assertThat(UpstoxApiClient.classifyTotpErrorResponse(
            "{\"errors\":[{\"errorCode\":\"UDAPI100101\",\"message\":\"secret\"}]}"))
            .isEqualTo("UPSTOX_TOTP_APP_NOT_ELIGIBLE");
        assertThat(UpstoxApiClient.classifyTotpErrorResponse(
            "{\"errors\":[{\"error_code\":\"UDAPI100096\"}]}"))
            .isEqualTo("UPSTOX_TOTP_APP_KEY_INVALID");
        assertThat(UpstoxApiClient.classifyTotpErrorResponse(
            "{\"errors\":[{\"errorCode\":\"UDAPI100097\"}]}"))
            .isEqualTo("UPSTOX_TOTP_CREDENTIALS_INVALID");
        assertThat(UpstoxApiClient.classifyTotpErrorResponse(
            "{\"errors\":[{\"errorCode\":\"UDAPI100098\"}]}"))
            .isEqualTo("UPSTOX_TOTP_NOT_ENABLED");
        assertThat(UpstoxApiClient.classifyTotpErrorResponse(
            "{\"errors\":[{\"errorCode\":\"UDAPI100099\"}]}"))
            .isEqualTo("UPSTOX_TOTP_RATE_LIMITED");
        assertThat(UpstoxApiClient.classifyTotpErrorResponse(
            "{\"errors\":[{\"errorCode\":\"UDAPI1242\"}]}"))
            .isEqualTo("UPSTOX_TOTP_CONFIGURATION_INVALID");
        assertThat(UpstoxApiClient.classifyTotpErrorResponse(
            "{\"errors\":[{\"errorCode\":\"UNKNOWN\",\"message\":\"pin=123456\"}]}"))
            .isEqualTo("UPSTOX_TOTP_AUTH_REJECTED");
        assertThat(UpstoxApiClient.classifyTotpErrorResponse("not json"))
            .isEqualTo("UPSTOX_TOTP_AUTH_REJECTED");
        assertThat(UpstoxApiClient.classifyTotpErrorResponse(" "))
            .isEqualTo("UPSTOX_TOTP_AUTH_REJECTED");
        assertThat(UpstoxApiClient.classifyTotpErrorResponse("x".repeat(4097)))
            .isEqualTo("UPSTOX_TOTP_AUTH_REJECTED");
    }

    @Test
    void generatesStandardRfcTotpWithoutExposingSecret() {
        assertThat(UpstoxTotp.generate("GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ",
            Instant.ofEpochSecond(59))).isEqualTo("287082");
        assertThatThrownBy(() -> UpstoxTotp.generate("invalid!", Instant.now()))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void ownerOnlyConfiguredLoginUsesTwoDecryptedSecureStrings() throws Exception {
        var api = mock(UpstoxApiClient.class);
        var sessions = mock(CustomerUpstoxOAuthService.class);
        var ssm = configuredSsm();
        var response = new ObjectMapper().readTree("{\"access_token\":\"secret-token\"}");
        when(sessions.status(OWNER)).thenReturn(disconnected());
        when(api.ownerTokenFromTotp(eq("individual-app-key"),
            eq("123456"), eq("287082"))).thenReturn(response);
        when(sessions.connectOwnerTotp(OWNER, response)).thenReturn(connected());
        var service = new OwnerUpstoxTotpService(sessions, APP, api, true, CLOCK, ssm);
        assertThat(service.available()).isTrue();
        assertThat(service.connect(OWNER).connected()).isTrue();
        var capture = org.mockito.ArgumentCaptor.forClass(GetParametersRequest.class);
        verify(ssm).getParameters(capture.capture());
        assertThat(capture.getValue().withDecryption()).isTrue();
        assertThat(capture.getValue().names()).containsExactlyInAnyOrder(
            "/minttick/upstox/login-pin", "/minttick/upstox/totp-secret");
        verify(api).ownerTokenFromTotp(eq("individual-app-key"),
            eq("123456"), eq("287082"));
    }

    @Test
    void neverInvalidatesAlreadyLiveSession() {
        var api = mock(UpstoxApiClient.class);
        var sessions = mock(CustomerUpstoxOAuthService.class);
        var ssm = mock(SsmClient.class);
        when(sessions.status(OWNER)).thenReturn(connected());
        var service = new OwnerUpstoxTotpService(sessions, APP, api, true, CLOCK, ssm);
        assertThat(service.connect(OWNER).connected()).isTrue();
        verifyNoInteractions(api, ssm);
    }

    @Test
    void deniedSsmAndRapidRetriesFailClosed() {
        var api = mock(UpstoxApiClient.class);
        var sessions = mock(CustomerUpstoxOAuthService.class);
        var ssm = mock(SsmClient.class);
        when(sessions.status(OWNER)).thenReturn(disconnected());
        when(ssm.getParameters(any(GetParametersRequest.class)))
            .thenThrow(new IllegalStateException("no access"));
        var service = new OwnerUpstoxTotpService(sessions, APP, api, true, CLOCK, ssm);
        assertThatThrownBy(() -> service.connect(OWNER))
            .isInstanceOf(UpstoxApiClient.UpstoxProviderException.class)
            .hasMessageContaining("UPSTOX_OWNER_SSM_UNAVAILABLE")
            .hasMessageNotContaining("no access");
        assertThatThrownBy(() -> service.connect(OWNER))
            .hasMessageContaining("UPSTOX_OWNER_LOGIN_COOLDOWN");
        verifyNoInteractions(api);
    }

    @Test
    void rejectsPlaintextPinAndCannotAutoLoginWhenDisabled() {
        var api = mock(UpstoxApiClient.class);
        var sessions = mock(CustomerUpstoxOAuthService.class);
        var ssm = mock(SsmClient.class);
        when(sessions.status(OWNER)).thenReturn(disconnected());
        when(ssm.getParameters(any(GetParametersRequest.class)))
            .thenReturn(GetParametersResponse.builder().parameters(
                Parameter.builder().name("/minttick/upstox/login-pin")
                    .type(ParameterType.STRING).value("123456").build(),
                Parameter.builder().name("/minttick/upstox/totp-secret")
                    .type(ParameterType.SECURE_STRING)
                    .value("GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ").build()
            ).build());
        var enabled = new OwnerUpstoxTotpService(sessions, APP, api, true, CLOCK, ssm);
        assertThatThrownBy(() -> enabled.connect(OWNER))
            .hasMessageContaining("UPSTOX_OWNER_SSM_INVALID");
        var disabled = new OwnerUpstoxTotpService(sessions, APP, api, false, CLOCK, ssm);
        assertThat(disabled.available()).isFalse();
        assertThatThrownBy(() -> disabled.connect(OWNER))
            .hasMessageContaining("UPSTOX_OWNER_AUTO_LOGIN_DISABLED");
        verifyNoInteractions(api);
    }
}
