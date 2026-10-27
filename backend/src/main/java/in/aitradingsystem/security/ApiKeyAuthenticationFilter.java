package in.aitradingsystem.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Authenticates the single operator with a server-side key. The key is never logged or echoed.
 * An empty configured key authenticates nobody (fail closed).
 */
@Component
public class ApiKeyAuthenticationFilter extends OncePerRequestFilter {
    public static final String HEADER = "X-API-Key";
    private static final Logger log = LoggerFactory.getLogger(ApiKeyAuthenticationFilter.class);

    private final byte[] expectedKey;

    public ApiKeyAuthenticationFilter(@Value("${app.security.api-key:}") String apiKey) {
        this.expectedKey = apiKey == null ? new byte[0] : apiKey.getBytes(StandardCharsets.UTF_8);
        if (expectedKey.length == 0) {
            log.warn("app.security.api-key is not set: protected API endpoints will reject all requests");
        }
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String presented = request.getHeader(HEADER);
        if (expectedKey.length > 0 && presented != null
                && MessageDigest.isEqual(expectedKey, presented.getBytes(StandardCharsets.UTF_8))) {
            SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                "operator", null, List.of(new SimpleGrantedAuthority("ROLE_OPERATOR"))));
        }
        chain.doFilter(request, response);
    }
}