package in.aitradingsystem.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

class ApiKeyAuthenticationFilterTest {

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void correctConfiguredKeyAuthenticatesOperator() throws Exception {
        ApiKeyAuthenticationFilter filter = new ApiKeyAuthenticationFilter("expected-secret");
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader(ApiKeyAuthenticationFilter.HEADER, "expected-secret");
        AtomicReference<Authentication> observed = new AtomicReference<>();

        filter.doFilter(request, new MockHttpServletResponse(), (req, res) ->
            observed.set(SecurityContextHolder.getContext().getAuthentication()));

        assertThat(observed.get()).isNotNull();
        assertThat(observed.get().getName()).isEqualTo("operator");
        assertThat(observed.get().getAuthorities()).extracting("authority").containsExactly("ROLE_OPERATOR");
    }

    @Test
    void wrongKeyFailsClosed() throws Exception {
        assertUnauthenticated(new ApiKeyAuthenticationFilter("expected-secret"), "wrong-secret");
    }

    @Test
    void missingKeyFailsClosed() throws Exception {
        assertUnauthenticated(new ApiKeyAuthenticationFilter("expected-secret"), null);
    }

    @Test
    void emptyConfiguredKeyAuthenticatesNobodyEvenWhenHeaderIsEmpty() throws Exception {
        assertUnauthenticated(new ApiKeyAuthenticationFilter(""), "");
    }

    @Test
    void nullConfiguredKeyAuthenticatesNobody() throws Exception {
        assertUnauthenticated(new ApiKeyAuthenticationFilter(null), "anything");
    }

    private static void assertUnauthenticated(ApiKeyAuthenticationFilter filter, String presented) throws Exception {
        SecurityContextHolder.clearContext();
        MockHttpServletRequest request = new MockHttpServletRequest();
        if (presented != null) {
            request.addHeader(ApiKeyAuthenticationFilter.HEADER, presented);
        }
        AtomicReference<Authentication> observed = new AtomicReference<>();

        filter.doFilter(request, new MockHttpServletResponse(), (req, res) ->
            observed.set(SecurityContextHolder.getContext().getAuthentication()));

        assertThat(observed.get()).isNull();
    }
}
