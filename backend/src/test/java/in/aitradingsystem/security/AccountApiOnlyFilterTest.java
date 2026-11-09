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
        assertPasses("GET", "/api/customer/market-data/instruments");
        assertPasses("GET", "/api/customer/market-data/instruments/status");
        assertPasses("GET", "/api/customer/market-data/quotes");
        assertPasses("GET", "/api/customer/market-data/snapshot");
        assertBlocked("POST", "/api/customer/market-data/snapshot");
        assertPasses("GET", "/api/customer/market-data/candles");
        assertBlocked("POST", "/api/customer/market-data/candles");
        assertPasses("GET", "/api/customer/market-data/quotes/detail");
        assertBlocked("POST", "/api/customer/market-data/quotes/detail");
        assertPasses("GET", "/api/customer/market-data/overview");
        assertBlocked("POST", "/api/customer/market-data/overview");
        assertPasses("GET", "/api/customer/watchlist");
        assertPasses("POST", "/api/customer/watchlist");
        assertPasses("OPTIONS", "/api/customer/watchlist");
        assertPasses("DELETE", "/api/customer/watchlist/items/42");
        assertPasses("OPTIONS", "/api/customer/watchlist/items/42");
        assertBlocked("PUT", "/api/customer/watchlist");
        assertBlocked("DELETE", "/api/customer/watchlist");
        assertBlocked("GET", "/api/customer/watchlist/items/42");
        assertBlocked("DELETE", "/api/customer/watchlist/items/abc");
        assertBlocked("DELETE", "/api/customer/watchlist/items/1234567890123456789");
        assertPasses("GET", "/api/customer/trading-rooms");
        assertPasses("POST", "/api/customer/trading-rooms");
        assertPasses("GET", "/api/customer/trading-rooms/12345678-1234-1234-1234-123456789012");
        assertPasses("DELETE", "/api/customer/trading-rooms/12345678-1234-1234-1234-123456789012");
        assertPasses("POST", "/api/customer/brokers/angel-one/connect");
        assertPasses("POST", "/api/customer/brokers/angel-one/disconnect");
        assertPasses("POST", "/api/customer/brokers/upstox/start");
        assertPasses("POST", "/api/customer/brokers/upstox/disconnect");
        assertPasses("GET", "/api/customer/brokers/upstox/callback");
        for (String route : List.of("status", "funds", "holdings", "positions",
                "orders", "registered-ips")) {
            assertPasses("GET", "/api/customer/brokers/upstox/" + route);
        }
        String roomId = "/api/customer/trading-rooms/12345678-1234-1234-1234-123456789012/paper/";
        assertPasses("POST", roomId + "activate");
        assertPasses("GET", roomId + "account");
        assertPasses("GET", roomId + "ledger");
        assertPasses("OPTIONS", roomId + "activate");
        String resource = "/api/customer/trading-rooms/12345678-1234-1234-1234-123456789012/";
        assertPasses("GET", resource + "execution/readiness");
        assertPasses("POST", resource + "orders/preflight");
        assertPasses("POST", resource + "orders/intents");
        assertPasses("GET", resource + "orders/intents");
        assertPasses("OPTIONS", resource + "orders/intents");
        assertPasses("OPTIONS", resource + "orders/preflight");
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
        assertBlocked("POST", "/api/customer/brokers/upstox/orders");
        assertBlocked("POST", "/api/customer/brokers/upstox/registered-ips");
        assertBlocked("GET", "/api/customer/brokers/upstox/start");
        assertBlocked("POST", "/api/customer/brokers/upstox/callback");
        assertBlocked("GET", "/api/customer/brokers/upstox/connect");
        assertBlocked("GET", "/api/customer/brokers/upstox/execute");
        String roomId = "/api/customer/trading-rooms/12345678-1234-1234-1234-123456789012/paper/";
        assertBlocked("POST", roomId + "orders");
        String resource = "/api/customer/trading-rooms/12345678-1234-1234-1234-123456789012/";
        assertBlocked("POST", resource + "orders/place");
        assertBlocked("PUT", resource + "orders/intents");
        assertBlocked("DELETE", resource + "orders/intents");
        assertBlocked("POST", resource + "orders/intents/execute");
        assertBlocked("POST", resource + "execution/readiness");
        assertBlocked("GET", resource + "orders/preflight");
        assertBlocked("DELETE", resource + "orders/preflight");
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
