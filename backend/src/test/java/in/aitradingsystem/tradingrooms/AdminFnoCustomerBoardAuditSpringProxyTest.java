package in.aitradingsystem.tradingrooms;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.aop.support.AopUtils;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.MapPropertySource;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.AuthenticationCredentialsNotFoundException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

class AdminFnoCustomerBoardAuditSpringProxyTest {

    @Configuration(proxyBeanMethods = false)
    @EnableMethodSecurity(proxyTargetClass = true)
    static class MethodSecurityConfig {}

    @Test
    void verifiedRightsStartRealMethodSecurityProxyAndRestrictOperatorRoute() {
        var discovery = mock(CustomerFnoDiscoveryController.class);
        when(discovery.internalCapability()).thenReturn(
            new CustomerFnoDiscoveryController.Capability(
                true, true, false, false, "SOURCE_UNAVAILABLE",
                "STOPPED", 0, null, false));
        try (var context = new AnnotationConfigApplicationContext()) {
            context.getEnvironment().getPropertySources().addFirst(
                new MapPropertySource("verified-fno-display-rights", Map.of(
                    "minttick.upstox-fno.display-rights-verified", "true")));
            context.register(MethodSecurityConfig.class,
                AdminFnoCustomerBoardAuditController.class);
            context.registerBean(CustomerFnoDiscoveryController.class,
                () -> discovery);
            context.refresh();

            var controller = context.getBean(AdminFnoCustomerBoardAuditController.class);
            // Regresses the EC2 boot failure: @PreAuthorize must be proxied.
            assertThat(AopUtils.isAopProxy(controller)).isTrue();
            assertThatThrownBy(controller::audit).isInstanceOfAny(
                AuthenticationCredentialsNotFoundException.class,
                AccessDeniedException.class);

            SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(
                    "operator", "unused",
                    List.of(new SimpleGrantedAuthority("ROLE_OPERATOR"))));
            try {
                var response = controller.audit();
                assertThat(response.getBody()).isNotNull();
                assertThat(response.getBody().status())
                    .isEqualTo("SOURCE_OR_RIGHTS_UNAVAILABLE");
                assertThat(response.getBody().safeNoOrders()).isTrue();
                assertThat(response.getHeaders().getCacheControl())
                    .contains("no-store");
                verify(discovery, never()).internalMarket(any());
            } finally {
                SecurityContextHolder.clearContext();
            }
        }
    }
}
