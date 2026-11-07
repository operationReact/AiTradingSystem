package in.aitradingsystem.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Set;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Public-edge isolation for the dedicated customer account deployment.
 * This does not rely on endpoint annotations or roles: unrelated trading
 * handlers, OpenAPI docs and operational endpoints never reach Spring MVC.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
@ConditionalOnProperty(name = "minttick.account-api-only.enabled", havingValue = "true")
public final class AccountApiOnlyFilter extends OncePerRequestFilter {
    private static final Set<String> AUTH_PATHS = Set.of(
        "/api/auth/cognito/provision",
        "/api/auth/cognito/me"
    );
    private static final Set<String> CUSTOMER_ROOM_COLLECTION = Set.of("/api/customer/trading-rooms");
    private static final Set<String> BROKER_READS = Set.of(
        "/api/customer/brokers/angel-one/status",
        "/api/customer/brokers/angel-one/funds",
        "/api/customer/brokers/angel-one/holdings",
        "/api/customer/brokers/angel-one/positions",
        "/api/customer/brokers/angel-one/orders");
    private static final Set<String> BROKER_WRITES = Set.of(
        "/api/customer/brokers/angel-one/connect",
        "/api/customer/brokers/angel-one/disconnect");
    private static final java.util.regex.Pattern CUSTOMER_ROOM_DETAIL =
        java.util.regex.Pattern.compile("^/api/customer/trading-rooms/[0-9a-fA-F-]{36}$");

    private static final java.util.regex.Pattern PAPER_WALLET_RESOURCE =
        java.util.regex.Pattern.compile(
            "^/api/customer/trading-rooms/[0-9a-fA-F-]{36}/paper/(activate|account|ledger)$");

    private static final Set<String> HEALTH_PATHS = Set.of(
        "/actuator/health",
        "/actuator/health/readiness",
        "/actuator/health/liveness"
    );

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        String uri = request.getRequestURI();
        String prefix = request.getContextPath();
        String path = uri.startsWith(prefix) ? uri.substring(prefix.length()) : "";
        String method = request.getMethod();
        boolean allowed = (AUTH_PATHS.contains(path)
                && ("GET".equals(method) || "POST".equals(method) || "OPTIONS".equals(method))
                && ("/api/auth/cognito/me".equals(path) ? !"POST".equals(method) : !"GET".equals(method)))
            || (HEALTH_PATHS.contains(path) && "GET".equals(method))
            || (CUSTOMER_ROOM_COLLECTION.contains(path)
                && Set.of("GET", "POST", "OPTIONS").contains(method))
            || (CUSTOMER_ROOM_DETAIL.matcher(path).matches()
                && Set.of("GET", "DELETE", "OPTIONS").contains(method))
            || (BROKER_READS.contains(path)
                && Set.of("GET", "OPTIONS").contains(method))
            || (BROKER_WRITES.contains(path)
                && Set.of("POST", "OPTIONS").contains(method))
            || (PAPER_WALLET_RESOURCE.matcher(path).matches()
                && ("OPTIONS".equals(method)
                    || (path.endsWith("/activate") && "POST".equals(method))
                    || (!path.endsWith("/activate") && "GET".equals(method))));
        if (!allowed) {
            response.sendError(HttpServletResponse.SC_NOT_FOUND);
            return;
        }
        chain.doFilter(request, response);
    }
}
