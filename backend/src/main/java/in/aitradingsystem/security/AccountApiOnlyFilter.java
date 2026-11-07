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
            || (HEALTH_PATHS.contains(path) && "GET".equals(method));
        if (!allowed) {
            response.sendError(HttpServletResponse.SC_NOT_FOUND);
            return;
        }
        chain.doFilter(request, response);
    }
}
