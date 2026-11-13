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
        "/api/customer/brokers/angel-one/configured-login",
        "/api/customer/brokers/angel-one/funds",
        "/api/customer/brokers/angel-one/holdings",
        "/api/customer/brokers/angel-one/positions",
        "/api/customer/brokers/angel-one/orders");
    private static final Set<String> CUSTOMER_MARKET_READS = Set.of(
        "/api/customer/market-data/instruments",
        "/api/customer/market-data/instruments/status",
        "/api/customer/market-data/quotes",
        "/api/customer/market-data/snapshot",
        "/api/customer/market-data/candles",
        "/api/customer/market-data/quotes/detail",
        "/api/customer/market-data/overview",
        "/api/customer/market-data/universe-facets",
        "/api/customer/market-data/full-quote-coverage",
        "/api/customer/market-data/board",
        "/api/customer/market-data/board/summary");
    private static final Set<String> PORTFOLIO_READS = Set.of(
        "/api/customer/portfolio/records",
        "/api/customer/home/v1/summary",
        "/api/customer/home/v1/activity");
    private static final String CUSTOMER_WATCHLIST = "/api/customer/watchlist";
    private static final java.util.regex.Pattern CUSTOMER_WATCHLIST_ITEM =
        java.util.regex.Pattern.compile("^/api/customer/watchlist/items/[0-9]{1,18}$");
    private static final Set<String> UPSTOX_READS = Set.of(
        "/api/customer/brokers/upstox/status",
        "/api/customer/brokers/upstox/configured-login",
        "/api/customer/brokers/upstox/funds",
        "/api/customer/brokers/upstox/holdings",
        "/api/customer/brokers/upstox/positions",
        "/api/customer/brokers/upstox/orders",
        "/api/customer/brokers/upstox/registered-ips");
    private static final Set<String> UPSTOX_WRITES = Set.of(
        "/api/customer/brokers/upstox/start",
        "/api/customer/brokers/upstox/connect-configured",
        "/api/customer/brokers/upstox/disconnect");
    private static final String UPSTOX_CALLBACK = "/api/customer/brokers/upstox/callback";
    private static final Set<String> BROKER_WRITES = Set.of(
        "/api/customer/brokers/angel-one/connect",
        "/api/customer/brokers/angel-one/connect-configured",
        "/api/customer/brokers/angel-one/disconnect");
    private static final java.util.regex.Pattern CUSTOMER_ROOM_DETAIL =
        java.util.regex.Pattern.compile("^/api/customer/trading-rooms/[0-9a-fA-F-]{36}$");

    private static final java.util.regex.Pattern PAPER_WALLET_RESOURCE =
        java.util.regex.Pattern.compile(
            "^/api/customer/trading-rooms/[0-9a-fA-F-]{36}/paper/(activate|account|ledger|resume|pause|orders|positions)$");

    private static final java.util.regex.Pattern CUSTOMER_EXECUTION_RESOURCE =
        java.util.regex.Pattern.compile(
            "^/api/customer/trading-rooms/[0-9a-fA-F-]{36}/(execution/readiness|orders/preflight)$");

    private static final java.util.regex.Pattern CUSTOMER_INTENT_RESOURCE =
        java.util.regex.Pattern.compile(
            "^/api/customer/trading-rooms/[0-9a-fA-F-]{36}/orders/intents$");

    /** Mintick Mind progress registry: read-only status plus owner notes (no status writes). */
    private static final String MIND_PROGRESS = "/api/customer/mind/v1/progress";
    private static final String MIND_PAPER_PORTFOLIO = "/api/customer/mind/v1/paper-portfolio";
    private static final String MIND_PAPER_JOURNAL = "/api/customer/mind/v1/paper-journal";
    private static final java.util.regex.Pattern MIND_PROGRESS_ITEM =
        java.util.regex.Pattern.compile(
            "^/api/customer/mind/v1/progress/items/[a-z]+\\.[A-Za-z0-9._-]{1,90}(/notes)?$");

    /** Owner-initiated Mind strategy cycle in a funded PAPER room (POST run, GET history). */
    private static final java.util.regex.Pattern MIND_PAPER_CYCLES =
        java.util.regex.Pattern.compile(
            "^/api/customer/trading-rooms/[0-9a-fA-F-]{36}/mind/paper-cycles$");

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
            || (CUSTOMER_WATCHLIST.equals(path)
                && Set.of("GET", "POST", "OPTIONS").contains(method))
            || (CUSTOMER_WATCHLIST_ITEM.matcher(path).matches()
                && Set.of("DELETE", "OPTIONS").contains(method))
            || (CUSTOMER_ROOM_DETAIL.matcher(path).matches()
                && Set.of("GET", "DELETE", "OPTIONS").contains(method))
            || (BROKER_READS.contains(path)
                && Set.of("GET", "OPTIONS").contains(method))
            || (PORTFOLIO_READS.contains(path)
                && Set.of("GET", "OPTIONS").contains(method))
            || (CUSTOMER_MARKET_READS.contains(path)
                && Set.of("GET", "OPTIONS").contains(method))
            || (BROKER_WRITES.contains(path)
                && Set.of("POST", "OPTIONS").contains(method))
            || (UPSTOX_READS.contains(path) && Set.of("GET", "OPTIONS").contains(method))
            || (UPSTOX_WRITES.contains(path) && Set.of("POST", "OPTIONS").contains(method))
            || (UPSTOX_CALLBACK.equals(path) && "GET".equals(method))
            || (PAPER_WALLET_RESOURCE.matcher(path).matches()
                && ("OPTIONS".equals(method)
                    || ((path.endsWith("/activate") || path.endsWith("/resume")
                        || path.endsWith("/pause")) && "POST".equals(method))
                    || (path.endsWith("/orders")
                        && ("GET".equals(method) || "POST".equals(method)))
                    || ((path.endsWith("/account") || path.endsWith("/ledger")
                        || path.endsWith("/positions")) && "GET".equals(method))))
            || (CUSTOMER_EXECUTION_RESOURCE.matcher(path).matches()
                && ("OPTIONS".equals(method)
                    || (path.endsWith("/execution/readiness") && "GET".equals(method))
                    || (path.endsWith("/orders/preflight") && "POST".equals(method))))
            || (CUSTOMER_INTENT_RESOURCE.matcher(path).matches()
                && Set.of("GET", "POST", "OPTIONS").contains(method))
            || (MIND_PAPER_CYCLES.matcher(path).matches()
                && Set.of("GET", "POST", "OPTIONS").contains(method))
            || (MIND_PROGRESS.equals(path) && Set.of("GET", "OPTIONS").contains(method))
            || (MIND_PAPER_PORTFOLIO.equals(path) && Set.of("GET", "OPTIONS").contains(method))
            || (MIND_PAPER_JOURNAL.equals(path) && Set.of("GET", "OPTIONS").contains(method))
            || (MIND_PROGRESS_ITEM.matcher(path).matches()
                && ("OPTIONS".equals(method)
                    || (path.endsWith("/notes") ? "POST".equals(method) : "GET".equals(method))));
        if (!allowed) {
            response.sendError(HttpServletResponse.SC_NOT_FOUND);
            return;
        }
        chain.doFilter(request, response);
    }
}
