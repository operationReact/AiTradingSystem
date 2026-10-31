package in.aitradingsystem.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
public class SessionAuthenticationFilter extends OncePerRequestFilter {
    public static final String COOKIE_NAME = "ATS_SESSION";
    public static final String CSRF_HEADER = "X-CSRF-Token";

    private final UserSessionStore sessions;

    public SessionAuthenticationFilter(UserSessionStore sessions) {
        this.sessions = sessions;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (SecurityContextHolder.getContext().getAuthentication() == null) {
            String rawToken = cookieValue(request, COOKIE_NAME);
            sessions.findActiveByToken(rawToken).ifPresent(session -> {
                SessionPrincipal principal = new SessionPrincipal(
                    session.userId(), session.externalSubject(), session.displayName(), session.email(),
                    session.role(), session.id());
                UsernamePasswordAuthenticationToken authentication = new UsernamePasswordAuthenticationToken(
                    principal, null, List.of(new SimpleGrantedAuthority("ROLE_" + session.role())));
                authentication.setDetails(session);
                SecurityContextHolder.getContext().setAuthentication(authentication);

                if (Duration.between(session.lastSeenAt(), OffsetDateTime.now(ZoneOffset.UTC)).toMinutes() >= 5) {
                    sessions.touch(session.id());
                }
            });
        }
        chain.doFilter(request, response);
    }

    static String cookieValue(HttpServletRequest request, String name) {
        Cookie[] cookies = request.getCookies();
        if (cookies == null) {
            return null;
        }
        for (Cookie cookie : cookies) {
            if (name.equals(cookie.getName())) {
                return cookie.getValue();
            }
        }
        return null;
    }

    public record SessionPrincipal(
        java.util.UUID userId,
        String externalSubject,
        String displayName,
        String email,
        String role,
        java.util.UUID sessionId
    ) {
        @Override
        public String toString() {
            return userId.toString();
        }
    }
}
