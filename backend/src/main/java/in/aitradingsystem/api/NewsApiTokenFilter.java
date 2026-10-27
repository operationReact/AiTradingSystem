package in.aitradingsystem.api;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
public class NewsApiTokenFilter extends OncePerRequestFilter {
    private final String configuredToken;
    private final String[] allowedOrigins;

    public NewsApiTokenFilter(@Value("${NEWS_API_TOKEN:}") String configuredToken,
        @Value("${app.news.cors-allowed-origins:http://localhost:5173}") String allowedOrigins) {
        this.configuredToken = configuredToken;
        this.allowedOrigins = Arrays.stream(allowedOrigins.split(",")).map(String::trim)
            .filter(origin -> !origin.isEmpty()).toArray(String[]::new);
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI().substring(request.getContextPath().length());
        // Protect only the provenance-feed root. Existing /api/news/articles and analysis routes use APP_API_KEY.
        return !"/api/news".equals(path);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
        FilterChain filterChain) throws ServletException, IOException {
        String origin = request.getHeader("Origin");
        boolean allowedOrigin = origin != null && Arrays.asList(allowedOrigins).contains(origin);
        if (allowedOrigin) {
            response.setHeader("Access-Control-Allow-Origin", origin);
            response.setHeader("Vary", "Origin");
            response.setHeader("Access-Control-Allow-Methods", "GET, OPTIONS");
            response.setHeader("Access-Control-Allow-Headers", "Authorization");
        }
        if ("OPTIONS".equalsIgnoreCase(request.getMethod()) && allowedOrigin) {
            response.setStatus(HttpServletResponse.SC_NO_CONTENT);
            return;
        }
        String header = request.getHeader("Authorization");
        String providedToken = header != null && header.startsWith("Bearer ")
            ? header.substring(7) : "";
        boolean valid = !configuredToken.isBlank() && !providedToken.isBlank()
            && MessageDigest.isEqual(configuredToken.getBytes(StandardCharsets.UTF_8),
                providedToken.getBytes(StandardCharsets.UTF_8));
        if (!valid) {
            response.setHeader("WWW-Authenticate", "Bearer");
            response.sendError(HttpServletResponse.SC_UNAUTHORIZED);
            return;
        }
        filterChain.doFilter(request, response);
    }
}
