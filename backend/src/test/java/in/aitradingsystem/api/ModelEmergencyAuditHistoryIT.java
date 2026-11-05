package in.aitradingsystem.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import in.aitradingsystem.support.AbstractPostgresIT;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;

class ModelEmergencyAuditHistoryIT extends AbstractPostgresIT {
    private static final String ROOT = "/api/admin/model-governance/emergency-controls";
    private final String actor = "v90-history-" + UUID.randomUUID().toString().substring(0, 8);

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired JdbcClient jdbc;

    @AfterEach
    void cleanup() throws Exception {
        mvc.perform(put(ROOT + "/static-fallback")
                .with(user("v90-cleanup").roles("ADMIN"))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"enabled\":false,\"reason\":\"V90 test cleanup\"}"))
            .andExpect(status().isOk());
        jdbc.sql("DELETE FROM audit_event WHERE actor = :actor OR actor = 'v90-cleanup'")
            .param("actor", actor)
            .update();
    }

    @Test
    void realAdminMutationAppearsAsBoundedPersistedReadOnlyHistory() throws Exception {
        mvc.perform(put(ROOT + "/static-fallback")
                .with(user(actor).roles("ADMIN"))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"enabled\":true,\"reason\":\"Market source validation incident\"}"))
            .andExpect(status().isOk());

        String result = mvc.perform(get(ROOT + "/history")
                .with(user(actor).roles("ADMIN"))
                .param("controlType", "STATIC_FALLBACK")
                .param("scopeKey", "GLOBAL")
                .param("limit", "50"))
            .andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString();
        JsonNode entries = mapper.readTree(result);
        assertThat(entries.isArray()).isTrue();
        JsonNode found = null;
        for (JsonNode row : entries) {
            if (actor.equals(row.path("actor").asText())) {
                found = row;
                break;
            }
        }
        assertThat(found).isNotNull();
        assertThat(found.path("controlType").asText()).isEqualTo("STATIC_FALLBACK");
        assertThat(found.path("scopeKey").asText()).isEqualTo("GLOBAL");
        assertThat(found.path("reason").asText()).isEqualTo("Market source validation incident");
        assertThat(found.path("active").asBoolean()).isTrue();
        assertThat(found.path("canAuthorizeCapital").asBoolean()).isFalse();
        assertThat(found.path("stateFingerprint").asText()).matches("[0-9a-f]{64}");
        assertThat(found.has("details")).isFalse();
        assertThat(found.has("token")).isFalse();
        assertThat(found.has("newConfigurationFingerprint")).isFalse();

        JsonNode one = mapper.readTree(mvc.perform(get(ROOT + "/history")
                .with(user(actor).roles("ADMIN"))
                .param("limit", "1"))
            .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertThat(one.size()).isEqualTo(1);
    }

    @Test
    void viewerTraderAndOperatorCannotAccessGovernanceHistory() throws Exception {
        for (String role : new String[] {"VIEWER", "TRADER", "OPERATOR"}) {
            mvc.perform(get(ROOT + "/history").with(user("not-admin").roles(role)))
                .andExpect(status().isForbidden());
        }
    }

    @Test
    void clientCannotInjectSqlOrEnumerateUnboundedEventHistory() throws Exception {
        for (String limit : new String[] {"0", "-1", "51"}) {
            mvc.perform(get(ROOT + "/history")
                    .with(user(actor).roles("ADMIN"))
                    .param("limit", limit))
                .andExpect(status().isBadRequest());
        }
        mvc.perform(get(ROOT + "/history")
                .with(user(actor).roles("ADMIN"))
                .param("controlType", "' OR 1=1 --"))
            .andExpect(status().isBadRequest());
        mvc.perform(get(ROOT + "/history")
                .with(user(actor).roles("ADMIN"))
                .param("scopeKey", "GLOBAL"))
            .andExpect(status().isBadRequest());
        mvc.perform(get(ROOT + "/history")
                .with(user(actor).roles("ADMIN"))
                .param("controlType", "STATIC_FALLBACK")
                .param("scopeKey", "../GLOBAL"))
            .andExpect(status().isBadRequest());
        mvc.perform(get(ROOT + "/history")
                .with(user(actor).roles("ADMIN"))
                .param("controlType", "STATIC_FALLBACK")
                .param("scopeKey", "BAD"))
            .andExpect(status().isBadRequest());
    }
}
