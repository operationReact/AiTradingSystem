package in.aitradingsystem.security;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class AccountApiOnlyFilterTest {
    private final AccountApiOnlyFilter filter = new AccountApiOnlyFilter();

    @Test
    void onlyRequiredIdentityAndReadinessRoutesAreReachable() throws Exception {
        for (String path : List.of("/api/auth/cognito/me", "/api/auth/cognito/provision",
                "/actuator/health", "/actuator/health/readiness", "/actuator/health/liveness")) {
            String method = path.endsWith("provision") ? "POST" : "GET";
            assertPasses(method, path);
        }
        assertPasses("OPTIONS", "/api/auth/cognito/me");
        assertPasses("OPTIONS", "/api/auth/cognito/provision");
        assertPasses("GET", "/api/customer/trading-rooms");
        assertPasses("POST", "/api/customer/trading-rooms");
        assertPasses("GET", "/api/customer/trading-rooms/12345678-1234-1234-1234-123456789012");
        assertPasses("DELETE", "/api/customer/trading-rooms/12345678-1234-1234-1234-123456789012");
        assertPasses("POST", "/api/customer/brokers/angel-one/connect");
        assertPasses("POST", "/api/customer/brokers/angel-one/disconnect");
        String roomId = "/api/customer/trading-rooms/12345678-1234-1234-1234-123456789012/paper/";
        assertPasses("POST", roomId + "activate");
        assertPasses("GET", roomId + "account");
        assertPasses("GET", roomId + "ledger");
        assertPasses("OPTIONS", roomId + "activate");
        for (String suffix : List.of("status", "funds", "holdings", "positions", "orders")) {
            assertPasses("GET", "/api/customer/brokers/angel-one/" + suffix);
        }
    }

    @Test
    void blocksTradingAdminDocsAndLegacyAuthentication() throws Exception {
        for (String path : List.of("/api/paper/orders", "/api/admin/users",
                "/api/health", "/swagger-ui.html", "/v3/api-docs",
                "/api/auth/session", "/actuator/prometheus", "/api/auth/cognito/me/")) {
            assertBlocked("GET", path);
        }
        assertBlocked("POST", "/api/auth/cognito/me");
        assertBlocked("GET", "/api/auth/cognito/provision");
        assertBlocked("DELETE", "/api/auth/cognito/provision");
        assertBlocked("OPTIONS", "/api/paper/orders");
        assertBlocked("POST", "/api/customer/brokers/angel-one/orders");
        String roomId = "/api/customer/trading-rooms/12345678-1234-1234-1234-123456789012/paper/";
        assertBlocked("POST", roomId + "orders");
        assertBlocked("GET", roomId + "activate");
        assertBlocked("POST", roomId + "account");
        assertBlocked("PUT", roomId + "ledger");
        assertBlocked("GET", "/api/customer/trading-rooms/not-a-uuid/paper/account");
        assertBlocked("POST", "/api/customer/brokers/angel-one/funds");
        assertBlocked("GET", "/api/customer/brokers/angel-one/connect");
        assertBlocked("GET", "/api/customer/trading-rooms/anything/else");
        assertBlocked("PATCH", "/api/customer/trading-rooms/12345678-1234-1234-1234-123456789012");
    }

    private void assertPasses(String method, String path) throws Exception {
        var request = new MockHttpServletRequest(method, path);
        var response = new MockHttpServletResponse();
        var chain = new MockFilterChain();
        filter.doFilter(request, response, chain);
        assertThat(chain.getRequest()).isNotNull();
    }

    private void assertBlocked(String method, String path) throws Exception {
        var request = new MockHttpServletRequest(method, path);
        var response = new MockHttpServletResponse();
        var chain = new MockFilterChain();
        filter.doFilter(request, response, chain);
        assertThat(response.getStatus()).isEqualTo(404);
        assertThat(chain.getRequest()).isNull();
    }
}
