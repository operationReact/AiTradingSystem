package in.aitradingsystem.provider.angelone;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import in.aitradingsystem.config.AngelOneProperties;
import in.aitradingsystem.user.CustomerIdentityStore;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.server.ResponseStatusException;

/**
 * The operator's existing SSM parameters are never a shared customer broker account.
 * Only a verified, allowlisted Cognito subject can invoke the configured connection.
 */
class CustomerAngelOneConfiguredLoginTest {
    private static final String ISSUER =
        "https://cognito-idp.ap-south-1.amazonaws.com/ap-south-1_test";
    private final CustomerIdentityStore identities = mock(CustomerIdentityStore.class);
    private final CustomerAngelOneSessionService service = mock(CustomerAngelOneSessionService.class);
    private final AngelOneProperties configured = new AngelOneProperties(
        "private-api-key", "AL1234", "1234", "JBSWY3DPEHPK3PXP",
        "https://apiconnect.angelone.in", 5000, 5000, 3600, 0, 0);

    private JwtAuthenticationToken principal(String subject) {
        var jwt = Jwt.withTokenValue("test-jwt")
            .header("alg", "RS256")
            .issuer(ISSUER)
            .subject(subject)
            .issuedAt(Instant.parse("2026-11-07T00:00:00Z"))
            .expiresAt(Instant.parse("2026-11-08T00:00:00Z"))
            .build();
        when(identities.find("cognito", ISSUER, subject)).thenReturn(Optional.of(
            new CustomerIdentityStore.CustomerAccount(UUID.randomUUID(),
                "ACTIVE", "CUSTOMER", "Test", "test@example.invalid")));
        return new JwtAuthenticationToken(jwt, List.of(
            new SimpleGrantedAuthority("ROLE_CUSTOMER")));
    }

    @Test
    void onlyTheConfiguredOwnerGetsTheSecureConnectAction() {
        var controller = new CustomerAngelOneController(identities, service, configured, "owner-sub");
        assertThat(controller.configuredLogin(principal("someone-else"))
            .getBody().available()).isFalse();
        assertThatThrownBy(() -> controller.connectConfigured(principal("someone-else")))
            .isInstanceOfSatisfying(ResponseStatusException.class,
                ex -> assertThat(ex.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN));
        verifyNoInteractions(service);
    }

    @Test
    void noOwnerBindingMeansFailClosedEvenWhenSSMParametersExist() {
        var controller = new CustomerAngelOneController(identities, service, configured, "");
        assertThat(controller.configuredLogin(principal("owner-sub"))
            .getBody().available()).isFalse();
        assertThatThrownBy(() -> controller.connectConfigured(principal("owner-sub")))
            .isInstanceOf(ResponseStatusException.class);
        verifyNoInteractions(service);
    }

    @Test
    void authorizedOwnerCanConnectWithoutSendingSecretsFromFlutter() {
        var controller = new CustomerAngelOneController(identities, service, configured, "owner-sub");
        var auth = principal("owner-sub");
        assertThat(controller.configuredLogin(auth).getBody().available()).isTrue();
        when(service.connect(any(UUID.class), eq("private-api-key"), eq("AL1234"),
            eq("1234"), matches("[0-9]{6}")))
            .thenReturn(new CustomerAngelOneSessionService.ConnectionStatus(
                true, "ANGEL_ONE", "***234", null, false));
        var result = controller.connectConfigured(auth).getBody();
        assertThat(result.connected()).isTrue();
        assertThat(result.orderExecutionEnabled()).isFalse();
        verify(service).connect(any(UUID.class), eq("private-api-key"), eq("AL1234"),
            eq("1234"), matches("[0-9]{6}"));
    }

    @Test
    void ownerConfiguredLoginUsesSsmOnEc2InsteadOfLambdaSession() {
        var ownerSsm = mock(OwnerAngelOneSsmService.class);
        var lambda = mock(OwnerAngelOneLambdaService.class);
        var live = mock(CustomerAngelOneLiveQuoteService.class);
        when(ownerSsm.available()).thenReturn(true);
        var noHostSecrets = new AngelOneProperties("", "", "", "",
            "https://apiconnect.angelone.in", 5000, 5000, 3600, 0, 0);
        var controller = new CustomerAngelOneController(identities, service, noHostSecrets,
            "owner-sub", lambda, ownerSsm, live);
        var auth = principal("owner-sub");
        assertThat(controller.configuredLogin(auth).getBody().available()).isTrue();
        when(ownerSsm.connect(any(UUID.class))).thenReturn(
            new CustomerAngelOneSessionService.ConnectionStatus(
                true, "ANGEL_ONE", "***234", Instant.now().plusSeconds(3000), false));

        var status = controller.connectConfigured(auth).getBody();
        assertThat(status.connected()).isTrue();
        assertThat(status.orderExecutionEnabled()).isFalse();
        verify(ownerSsm).connect(any(UUID.class));
        verify(lambda, never()).connect(any(UUID.class));
        verify(service, never()).connect(any(UUID.class), anyString(), anyString(),
                anyString(), anyString());
    }

    @Test
    void nonOwnerCannotUseConfiguredSsmLogin() {
        var ownerSsm = mock(OwnerAngelOneSsmService.class);
        when(ownerSsm.available()).thenReturn(true);
        var noHostSecrets = new AngelOneProperties("", "", "", "",
            "https://apiconnect.angelone.in", 5000, 5000, 3600, 0, 0);
        var controller = new CustomerAngelOneController(identities, service, noHostSecrets,
            "owner-sub", null, ownerSsm, null);
        assertThat(controller.configuredLogin(principal("other-sub")).getBody().available())
            .isFalse();
        assertThatThrownBy(() -> controller.connectConfigured(principal("other-sub")))
            .isInstanceOfSatisfying(ResponseStatusException.class,
                ex -> assertThat(ex.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN));
        verify(ownerSsm, never()).connect(any(UUID.class));
    }
}
