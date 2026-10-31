package in.aitradingsystem.security;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import in.aitradingsystem.support.AbstractPostgresIT;
import in.aitradingsystem.user.UserAccountStore;
import jakarta.servlet.http.Cookie;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;

class SecurityExitGateIT extends AbstractPostgresIT {

    @Autowired MockMvc mvc;
    @Autowired UserAccountStore users;
    @Autowired UserSessionStore sessions;
    @Autowired JdbcClient jdbc;

    @Test
    void unauthenticatedApiReadsAndMutationsAreRejected() throws Exception {
        mvc.perform(get("/api/operations/health"))
            .andExpect(status().isUnauthorized());
        mvc.perform(post("/api/research/run"))
            .andExpect(status().isUnauthorized());
    }

    @Test
    void allowedCorsPreflightSucceedsAndUnknownOriginFailsClosed() throws Exception {
        mvc.perform(options("/api/operations/health")
                .header(HttpHeaders.ORIGIN, "http://localhost:5173")
                .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "GET"))
            .andExpect(status().isOk())
            .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, "http://localhost:5173"))
            .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_CREDENTIALS, "true"));

        mvc.perform(options("/api/operations/health")
                .header(HttpHeaders.ORIGIN, "https://evil.example")
                .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "GET"))
            .andExpect(status().isForbidden());
    }

    @Test
    void secureResponsesCarryRequiredBrowserSecurityHeaders() throws Exception {
        mvc.perform(get("/api/health").secure(true))
            .andExpect(status().isOk())
            .andExpect(header().exists(HttpHeaders.STRICT_TRANSPORT_SECURITY))
            .andExpect(header().string("Content-Security-Policy", containsString("default-src 'none'")))
            .andExpect(header().string("X-Frame-Options", "DENY"))
            .andExpect(header().string("Referrer-Policy", "no-referrer"))
            .andExpect(header().string("X-Content-Type-Options", "nosniff"));
    }

    @Test
    void expiredSessionCannotAuthenticate() throws Exception {
        var user = users.createUser(
            "security-exit-" + UUID.randomUUID(), "Security Exit", null, "test:security-exit");
        var issued = sessions.create(user.id(), Duration.ofMinutes(5), "test-agent", "127.0.0.1");
        Cookie cookie = new Cookie(SessionAuthenticationFilter.COOKIE_NAME, issued.rawToken());

        mvc.perform(get("/api/auth/session").cookie(cookie))
            .andExpect(status().isOk());

        jdbc.sql("UPDATE user_session SET expires_at = now() - interval '1 second' WHERE id = :id")
            .param("id", issued.id())
            .update();

        mvc.perform(get("/api/auth/session").cookie(cookie))
            .andExpect(status().isUnauthorized());
    }

    @Test
    void revokedSessionCannotAuthenticate() throws Exception {
        var user = users.createUser(
            "security-revoke-" + UUID.randomUUID(), "Security Revoke", null, "test:security-exit");
        var issued = sessions.create(user.id(), Duration.ofMinutes(5), "test-agent", "127.0.0.1");
        Cookie cookie = new Cookie(SessionAuthenticationFilter.COOKIE_NAME, issued.rawToken());

        mvc.perform(get("/api/auth/session").cookie(cookie))
            .andExpect(status().isOk());

        sessions.revoke(issued.id());

        mvc.perform(get("/api/auth/session").cookie(cookie))
            .andExpect(status().isUnauthorized());
    }

    @Test
    void devSessionBootstrapIsUnavailableUnlessExplicitlyEnabled() throws Exception {
        mvc.perform(post("/api/auth/dev/session")
                .header(ApiKeyAuthenticationFilter.HEADER, AbstractPostgresIT.API_KEY))
            .andExpect(status().isNotFound());
    }
}
