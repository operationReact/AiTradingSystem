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

/** Real Spring method-security and PostgreSQL checks for default-off roster dispatch. */
class ResearchRosterDispatchControllerIT extends AbstractPostgresIT {
    @Autowired MockMvc mvc;
    @Autowired JdbcClient jdbc;

    private String path() {
        return "/api/admin/model-governance/paper-shadow/sessions/"
            + UUID.randomUUID() + "/cases/CASE-001/dispatch-research-roster";
    }

    @Test
    void onlyAdminMayRequestRosterDispatch() throws Exception {
        String endpoint = path();
        for (String role : new String[]{"VIEWER", "TRADER", "OPERATOR"}) {
            mvc.perform(post(endpoint).with(user("not-admin").roles(role)))
                .andExpect(status().isForbidden());
        }
    }

    @Test
    void defaultDisabledCannotReserveAnyModelOrCallPython() throws Exception {
        int before = jdbc.sql(
            "SELECT count(*) FROM v4_research_inference_reservation")
            .query(Integer.class).single();
        mvc.perform(post(path()).with(user("research-admin").roles("ADMIN")))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.detail").value(
                "Research roster disabled, unsafe, already attempted or interrupted"));
        assertEquals(before, jdbc.sql(
            "SELECT count(*) FROM v4_research_inference_reservation")
            .query(Integer.class).single());
    }
}
