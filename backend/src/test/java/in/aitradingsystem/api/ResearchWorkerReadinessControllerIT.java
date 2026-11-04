package in.aitradingsystem.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import in.aitradingsystem.support.AbstractPostgresIT;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;

/** Real Spring security and PostgreSQL default-off V72 readiness boundary. */
class ResearchWorkerReadinessControllerIT extends AbstractPostgresIT {
    @Autowired MockMvc mvc;
    @Autowired JdbcClient jdbc;

    private static String path() {
        return "/api/admin/model-governance/paper-shadow/sessions/"
            + UUID.randomUUID() + "/worker-readiness";
    }

    @Test
    void nonAdminCannotInspectWorkerArtifactMetadata() throws Exception {
        String route = path();
        for (String role : new String[]{"VIEWER", "TRADER", "OPERATOR"}) {
            mvc.perform(get(route).with(user("not-admin").roles(role)))
                .andExpect(status().isForbidden());
        }
    }

    @Test
    void defaultDisabledReadinessCannotCreateResearchReservation()
            throws Exception {
        int before = jdbc.sql("""
                SELECT count(*) FROM v4_research_inference_reservation
                """).query(Integer.class).single();
        mvc.perform(get(path()).with(user("readiness-admin").roles("ADMIN")))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.detail").value(
                "Research worker readiness disabled, unavailable or evidence unsafe"));
        assertEquals(before, jdbc.sql("""
                SELECT count(*) FROM v4_research_inference_reservation
                """).query(Integer.class).single());
    }
}
