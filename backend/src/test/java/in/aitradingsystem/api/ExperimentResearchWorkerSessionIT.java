package in.aitradingsystem.api;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import in.aitradingsystem.security.UserSessionStore;
import in.aitradingsystem.support.AbstractPostgresIT;
import in.aitradingsystem.user.UserAccountStore;
import jakarta.servlet.http.Cookie;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;

/** Exercises the real Spring session, role, and CSRF filters, not a mocked Authentication. */
class ExperimentResearchWorkerSessionIT extends AbstractPostgresIT {
    @Autowired MockMvc mvc;
    @Autowired UserAccountStore users;
    @Autowired UserSessionStore sessions;
    @Autowired JdbcClient jdbc;

    @Test
    void onlyRealAdminSessionCanReadExperimentJobs() throws Exception {
        var issued = issueAdminSession();
        mvc.perform(get("/api/admin/model-governance/experiment-jobs")
                .cookie(sessionCookie(issued)))
            .andExpect(status().isOk());

        mvc.perform(get("/api/admin/model-governance/experiment-jobs")
                .header("Authorization", "Bearer invalid-test-token"))
            .andExpect(status().isUnauthorized());

        mvc.perform(get("/api/admin/model-governance/experiment-jobs")
                .header("X-API-Key", API_KEY))
            .andExpect(status().isForbidden());
    }

    @Test
    void adminSessionRequiresCsrfForPythonWorkerMutation() throws Exception {
        var issued = issueAdminSession();
        String path = "/api/admin/model-governance/experiment-jobs/"
            + UUID.randomUUID() + "/start";
        String body = "{\"claimToken\":\"" + UUID.randomUUID() + "\"}";

        mvc.perform(post(path)
                .cookie(sessionCookie(issued))
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
            .andExpect(status().isForbidden());

        mvc.perform(post(path)
                .cookie(sessionCookie(issued))
                .header("X-CSRF-Token", "invalid-test-csrf")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
            .andExpect(status().isForbidden());

        // 404 proves the authenticated, CSRF-validated call reached the
        // governance controller. It must not create or mutate any job.
        mvc.perform(post(path)
                .cookie(sessionCookie(issued))
                .header("X-CSRF-Token", issued.csrfToken())
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
            .andExpect(status().isNotFound());
    }

    private UserSessionStore.IssuedSession issueAdminSession() {
        var user = users.createUser(
            "test-v403-admin-" + UUID.randomUUID(), "V4 Research Admin",
            null, "system:test");
        jdbc.sql("UPDATE app_user SET role = 'ADMIN' WHERE id = :id")
            .param("id", user.id())
            .update();
        return sessions.create(user.id(), Duration.ofMinutes(5), "integration-test", "127.0.0.1");
    }

    private static Cookie sessionCookie(UserSessionStore.IssuedSession session) {
        return new Cookie("ATS_SESSION", session.rawToken());
    }
}
