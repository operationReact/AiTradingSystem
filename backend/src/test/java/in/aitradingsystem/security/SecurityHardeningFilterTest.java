package in.aitradingsystem.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import jakarta.servlet.http.HttpServletResponse;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class SecurityHardeningFilterTest {

    @Test
    void rejectsDeclaredRequestBodyAboveConfiguredLimit() throws Exception {
        SecurityHardeningFilter filter = new SecurityHardeningFilter(1024, 10, 60, 60);
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/research/run");
        request.setContent(new byte[1025]);
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, new MockFilterChain());

        assertThat(response.getStatus()).isEqualTo(HttpServletResponse.SC_REQUEST_ENTITY_TOO_LARGE);
        assertThat(response.getContentAsString()).contains("REQUEST_TOO_LARGE");
    }

    @Test
    void rateLimitsAuthenticationBootstrapByClientAndWindow() throws Exception {
        SecurityHardeningFilter filter = new SecurityHardeningFilter(1024, 1, 60, 60);

        MockHttpServletResponse first = invoke(filter, "POST", "/api/auth/dev/session", "10.0.0.1");
        MockHttpServletResponse second = invoke(filter, "POST", "/api/auth/dev/session", "10.0.0.1");
        MockHttpServletResponse otherClient = invoke(filter, "POST", "/api/auth/dev/session", "10.0.0.2");

        assertThat(first.getStatus()).isEqualTo(HttpServletResponse.SC_OK);
        assertThat(second.getStatus()).isEqualTo(429);
        assertThat(second.getHeader("Retry-After")).isNotBlank();
        assertThat(otherClient.getStatus()).isEqualTo(HttpServletResponse.SC_OK);
    }

    @Test
    void readOnlyRequestsAreNotRateLimitedBySensitiveMutationLimit() throws Exception {
        SecurityHardeningFilter filter = new SecurityHardeningFilter(1024, 1, 1, 60);

        MockHttpServletResponse first = invoke(filter, "GET", "/api/risk/config", "10.0.0.1");
        MockHttpServletResponse second = invoke(filter, "GET", "/api/risk/config", "10.0.0.1");

        assertThat(first.getStatus()).isEqualTo(HttpServletResponse.SC_OK);
        assertThat(second.getStatus()).isEqualTo(HttpServletResponse.SC_OK);
    }

    @Test
    void credentialedCorsRejectsWildcardsAndMalformedOrigins() {
        SecurityConfig config = new SecurityConfig();

        assertThatThrownBy(() -> config.corsSource(List.of("*")))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("Wildcard CORS");
        assertThatThrownBy(() -> config.corsSource(List.of("file:///tmp/app")))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("Invalid CORS origin");

        assertThat(config.corsSource(List.of("https://maarglobal.com"))).isNotNull();
    }

    private MockHttpServletResponse invoke(
            SecurityHardeningFilter filter, String method, String path, String clientAddress) throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest(method, path);
        request.setRemoteAddr(clientAddress);
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(request, response, new MockFilterChain());
        return response;
    }
}
