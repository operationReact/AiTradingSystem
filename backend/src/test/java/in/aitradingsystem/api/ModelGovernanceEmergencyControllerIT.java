package in.aitradingsystem.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import in.aitradingsystem.support.AbstractPostgresIT;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;

class ModelGovernanceEmergencyControllerIT extends AbstractPostgresIT {
    private static final String HASH_A = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";
    private static final String HASH_B = "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb";
    private static final String HASH_C = "cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc";
    private static final String HASH_D = "dddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddd";
    private static final String CODE_SHA = "0123456789abcdef0123456789abcdef01234567";

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired JdbcClient jdbc;

    @BeforeEach
    void restoreNormalFusionMode() throws Exception {
        mvc.perform(put("/api/admin/model-governance/emergency-controls/static-fallback")
                .with(user("test-admin").roles("ADMIN"))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"enabled\":false,\"reason\":\"test isolation\"}"))
            .andExpect(status().isOk());
    }

    @Test
    void adminCanDisableAndReenableExactModelVersionWithAuditedFingerprints() throws Exception {
        RegisteredModel model = registerModel();

        mvc.perform(get("/api/admin/model-governance/emergency-controls/models/" + model.versionId() + "/effective")
                .with(user("governance-admin").roles("ADMIN")))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.allowed").value(true))
            .andExpect(jsonPath("$.canAuthorizeCapital").value(false));

        mvc.perform(put("/api/admin/model-governance/emergency-controls/models/" + model.versionId())
                .with(user("governance-admin").roles("ADMIN"))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"disabled\":true,\"reason\":\"latency anomaly\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.controlType").value("MODEL_VERSION_DISABLED"))
            .andExpect(jsonPath("$.active").value(true))
            .andExpect(jsonPath("$.updatedBy").value("governance-admin"))
            .andExpect(jsonPath("$.canAuthorizeCapital").value(false));

        mvc.perform(get("/api/admin/model-governance/emergency-controls/models/" + model.versionId() + "/effective")
                .with(user("governance-admin").roles("ADMIN")))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.allowed").value(false))
            .andExpect(jsonPath("$.modelVersionDisabled").value(true));

        String details = jdbc.sql("""
                SELECT details::text
                  FROM audit_event
                 WHERE event_type = 'MODEL_VERSION_EMERGENCY_CONTROL_CHANGED'
                   AND actor = 'governance-admin'
                   AND entity_id = :versionId
                 ORDER BY created_at DESC
                 LIMIT 1
                """)
            .param("versionId", model.versionId().toString())
            .query(String.class)
            .single();
        JsonNode audit = mapper.readTree(details);
        assertEquals("latency anomaly", audit.path("reason").asText());
        assertEquals("ACCEPTED", audit.path("backendValidationResult").asText());
        assertEquals(false, audit.path("canAuthorizeCapital").asBoolean());
        assertNotEquals(
            audit.path("previousConfigurationFingerprint").asText(),
            audit.path("newConfigurationFingerprint").asText());

        mvc.perform(put("/api/admin/model-governance/emergency-controls/models/" + model.versionId())
                .with(user("governance-admin").roles("ADMIN"))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"disabled\":false,\"reason\":\"validation recovered\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.active").value(false));

        mvc.perform(get("/api/admin/model-governance/emergency-controls/models/" + model.versionId() + "/effective")
                .with(user("governance-admin").roles("ADMIN")))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.allowed").value(true));
    }

    @Test
    void familyDisableFailsClosedForEveryRegisteredVersionInFamily() throws Exception {
        String family = uniqueFamily();
        RegisteredModel first = registerModel(family);
        RegisteredModel second = registerModel(family);

        mvc.perform(put("/api/admin/model-governance/emergency-controls/families/" + family)
                .with(user("governance-admin").roles("ADMIN"))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"disabled\":true,\"reason\":\"family drift incident\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.controlType").value("MODEL_FAMILY_DISABLED"))
            .andExpect(jsonPath("$.scopeKey").value(family))
            .andExpect(jsonPath("$.active").value(true));

        for (UUID versionId : new UUID[]{first.versionId(), second.versionId()}) {
            mvc.perform(get("/api/admin/model-governance/emergency-controls/models/" + versionId + "/effective")
                    .with(user("governance-admin").roles("ADMIN")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.allowed").value(false))
                .andExpect(jsonPath("$.modelFamilyDisabled").value(true));
        }
    }

    @Test
    void staticFallbackDisablesModelInfluenceWithoutChangingPaperTradingAuthority() throws Exception {
        RegisteredModel model = registerModel();
        String modeBefore = jdbc.sql("SELECT active_mode FROM trading_execution_mode WHERE id = 1")
            .query(String.class)
            .single();
        assertEquals("PAPER", modeBefore);

        mvc.perform(put("/api/admin/model-governance/emergency-controls/static-fallback")
                .with(user("governance-admin").roles("ADMIN"))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"enabled\":true,\"reason\":\"model-wide incident\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.controlType").value("STATIC_FALLBACK"))
            .andExpect(jsonPath("$.scopeKey").value("GLOBAL"))
            .andExpect(jsonPath("$.active").value(true))
            .andExpect(jsonPath("$.canAuthorizeCapital").value(false));

        mvc.perform(get("/api/admin/model-governance/emergency-controls/models/" + model.versionId() + "/effective")
                .with(user("governance-admin").roles("ADMIN")))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.allowed").value(false))
            .andExpect(jsonPath("$.staticFallbackEnabled").value(true));

        String modeAfter = jdbc.sql("SELECT active_mode FROM trading_execution_mode WHERE id = 1")
            .query(String.class)
            .single();
        assertEquals("PAPER", modeAfter);
    }

    @Test
    void unknownTargetsAndMissingReasonAreRejected() throws Exception {
        mvc.perform(put("/api/admin/model-governance/emergency-controls/models/" + UUID.randomUUID())
                .with(user("admin").roles("ADMIN"))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"disabled\":true,\"reason\":\"incident\"}"))
            .andExpect(status().isNotFound());

        mvc.perform(put("/api/admin/model-governance/emergency-controls/families/DOES_NOT_EXIST")
                .with(user("admin").roles("ADMIN"))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"disabled\":true,\"reason\":\"incident\"}"))
            .andExpect(status().isNotFound());

        mvc.perform(put("/api/admin/model-governance/emergency-controls/static-fallback")
                .with(user("admin").roles("ADMIN"))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"enabled\":true,\"reason\":\"\"}"))
            .andExpect(status().isBadRequest());
    }

    @Test
    void viewerTraderAndLegacyOperatorCannotUseEmergencyControls() throws Exception {
        for (String role : new String[]{"VIEWER", "TRADER", "OPERATOR"}) {
            mvc.perform(get("/api/admin/model-governance/emergency-controls")
                    .with(user(role.toLowerCase()).roles(role)))
                .andExpect(status().isForbidden());
            mvc.perform(put("/api/admin/model-governance/emergency-controls/static-fallback")
                    .with(user(role.toLowerCase()).roles(role))
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"enabled\":true,\"reason\":\"not authorized\"}"))
                .andExpect(status().isForbidden());
        }
    }

    private RegisteredModel registerModel() throws Exception {
        return registerModel(uniqueFamily());
    }

    private RegisteredModel registerModel(String family) throws Exception {
        String key = "control-model-" + UUID.randomUUID().toString().substring(0, 8);
        mvc.perform(post("/api/admin/model-governance/models")
                .with(user("setup-admin").roles("ADMIN"))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "modelKey": "%s",
                      "displayName": "Emergency Control Test Model",
                      "family": "%s",
                      "role": "ALPHA",
                      "provider": "IN_HOUSE",
                      "licenseStatus": "APPROVED_COMMERCIAL"
                    }
                    """.formatted(key, family)))
            .andExpect(status().isCreated());

        String response = mvc.perform(post("/api/admin/model-governance/models/" + key + "/versions")
                .with(user("setup-admin").roles("ADMIN"))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "versionLabel": "v1",
                      "artifactFingerprint": "%s",
                      "featureSchemaFingerprint": "%s",
                      "trainingDatasetFingerprint": "%s",
                      "trainingConfigFingerprint": "%s",
                      "codeSha": "%s"
                    }
                    """.formatted(HASH_A, HASH_B, HASH_C, HASH_D, CODE_SHA)))
            .andExpect(status().isCreated())
            .andReturn()
            .getResponse()
            .getContentAsString();
        UUID versionId = UUID.fromString(mapper.readTree(response).path("versionId").asText());
        return new RegisteredModel(key, family, versionId);
    }

    private static String uniqueFamily() {
        return "FAM_" + UUID.randomUUID().toString().replace("-", "").substring(0, 8).toUpperCase();
    }

    private record RegisteredModel(String modelKey, String family, UUID versionId) {}
}
