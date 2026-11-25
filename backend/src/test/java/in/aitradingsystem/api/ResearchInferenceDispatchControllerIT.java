package in.aitradingsystem.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import in.aitradingsystem.support.AbstractPostgresIT;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;

/** Default-off endpoint guards: no credentials or LIVE mode can enable dispatch. */
class ResearchInferenceDispatchControllerIT extends AbstractPostgresIT {
    @Autowired MockMvc mvc;
    @Autowired JdbcClient jdbc;

    private static String url() {
        return "/api/admin/model-governance/paper-shadow/sessions/"
            + UUID.randomUUID() + "/cases/CASE-001/models/"
            + UUID.randomUUID() + "/research-inference";
    }

    @Test
    void nonAdminRolesCannotTriggerAnyWorkerOrReserveAttempts() throws Exception {
        String endpoint = url();
        for (String role : new String[]{"OPERATOR", "TRADER", "VIEWER"}) {
            mvc.perform(post(endpoint).with(user("not-admin").roles(role)))
                .andExpect(status().isForbidden());
        }
        assertEquals(0, jdbc.sql("""
            SELECT count(*) FROM v4_research_inference_reservation
             WHERE reserved_by = 'not-admin'
            """).query(Integer.class).single());
    }

    @Test
    void defaultDisabledAdminCannotReserveOrInvokeModel() throws Exception {
        int before = jdbc.sql(
            "SELECT count(*) FROM v4_research_inference_reservation")
            .query(Integer.class).single();
        mvc.perform(post(url()).with(user("research-admin").roles("ADMIN")))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.detail").value(
                "Research inference is disabled, unsafe, or already attempted"));
        assertEquals(before, jdbc.sql(
            "SELECT count(*) FROM v4_research_inference_reservation")
            .query(Integer.class).single());
    }
}
