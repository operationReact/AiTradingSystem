package in.aitradingsystem.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.Instant;
import java.util.Locale;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
public class SecurityHardeningFilter extends OncePerRequestFilter {
    private final long maxRequestBytes;
    private final int authRequestsPerWindow;
    private final int sensitiveRequestsPerWindow;
    private final long windowSeconds;
    private final ConcurrentHashMap<String, WindowCounter> counters = new ConcurrentHashMap<>();

    public SecurityHardeningFilter(
            @Value("${app.security.max-request-bytes:1048576}") long maxRequestBytes,
            @Value("${app.security.rate-limit.auth-requests-per-window:10}") int authRequestsPerWindow,
            @Value("${app.security.rate-limit.sensitive-requests-per-window:60}") int sensitiveRequestsPerWindow,
            @Value("${app.security.rate-limit.window-seconds:60}") long windowSeconds) {
        if (maxRequestBytes < 1024 || authRequestsPerWindow < 1 || sensitiveRequestsPerWindow < 1 || windowSeconds < 1) {
            throw new IllegalArgumentException("Invalid security hardening limits");
        }
        this.maxRequestBytes = maxRequestBytes;
        this.authRequestsPerWindow = authRequestsPerWindow;
        this.sensitiveRequestsPerWindow = sensitiveRequestsPerWindow;
        this.windowSeconds = windowSeconds;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        long contentLength = request.getContentLengthLong();
        if (contentLength > maxRequestBytes) {
            reject(response, HttpServletResponse.SC_REQUEST_ENTITY_TOO_LARGE, "REQUEST_TOO_LARGE", null);
            return;
        }

        RateClass rateClass = classify(request);
        if (rateClass != RateClass.NONE && exceeded(request, rateClass)) {
            long retryAfter = secondsUntilNextWindow();
            response.setHeader("Retry-After", Long.toString(retryAfter));
            reject(response, HttpServletResponse.SC_TOO_MANY_REQUESTS, "RATE_LIMITED", retryAfter);
            return;
        }

        filterChain.doFilter(request, response);
    }

    private RateClass classify(HttpServletRequest request) {
        String method = request.getMethod().toUpperCase(Locale.ROOT);
        if (!(method.equals("POST") || method.equals("PUT") || method.equals("PATCH") || method.equals("DELETE"))) {
            return RateClass.NONE;
        }

        String path = request.getRequestURI();
        if (path.equals("/api/auth/dev/session")) {
            return RateClass.AUTH;
        }
        if (path.startsWith("/api/auth/") || path.startsWith("/api/risk/")
                || path.startsWith("/api/execution-policy/") || path.startsWith("/api/users/")
                || path.startsWith("/api/admin/")) {
            return RateClass.SENSITIVE;
        }
        return RateClass.NONE;
    }

    private boolean exceeded(HttpServletRequest request, RateClass rateClass) {
        long window = Instant.now().getEpochSecond() / windowSeconds;
        String key = clientAddress(request) + ":" + rateClass + ":" + window;
        int limit = rateClass == RateClass.AUTH ? authRequestsPerWindow : sensitiveRequestsPerWindow;
        WindowCounter counter = counters.computeIfAbsent(key, ignored -> new WindowCounter(window));
        int current = counter.count.incrementAndGet();
        if (counters.size() > 10_000) {
            counters.entrySet().removeIf(entry -> entry.getValue().window < window - 1);
        }
        return current > limit;
    }

    private String clientAddress(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            return forwarded.split(",", 2)[0].trim();
        }
        return request.getRemoteAddr();
    }

    private long secondsUntilNextWindow() {
        long now = Instant.now().getEpochSecond();
        long remainder = now % windowSeconds;
        return Math.max(1, windowSeconds - remainder);
    }

    private void reject(HttpServletResponse response, int status, String code, Long retryAfter) throws IOException {
        response.setStatus(status);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        String suffix = retryAfter == null ? "" : ",\"retryAfterSeconds\":" + retryAfter;
        response.getWriter().write("{\"error\":\"" + code + "\"" + suffix + "}");
    }

    enum RateClass { NONE, AUTH, SENSITIVE }

    private static final class WindowCounter {
        private final long window;
        private final AtomicInteger count = new AtomicInteger();

        private WindowCounter(long window) {
            this.window = window;
        }
    }
}
