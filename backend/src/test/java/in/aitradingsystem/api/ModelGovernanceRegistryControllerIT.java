package in.aitradingsystem.api;

import static org.hamcrest.Matchers.hasItem;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import in.aitradingsystem.support.AbstractPostgresIT;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;

class ModelGovernanceRegistryControllerIT extends AbstractPostgresIT {
    private static final String HASH_A = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";
    private static final String HASH_B = "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb";
    private static final String HASH_C = "cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc";
    private static final String HASH_D = "dddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddd";
    private static final String HASH_E = "eeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeee";
    private static final String CODE_SHA = "0123456789abcdef0123456789abcdef01234567";

    @Autowired MockMvc mvc;
    @Autowired JdbcClient jdbc;

    @Test
    void adminRegistersImmutableDefinitionAndVersionWithAuditEvidence() throws Exception {
        String key = "catboost-alpha-" + UUID.randomUUID().toString().substring(0, 8);
        String definition = definitionJson(key);

        mvc.perform(post("/api/admin/model-governance/models")
                .with(user("registry-admin").roles("ADMIN"))
                .contentType(MediaType.APPLICATION_JSON)
                .content(definition))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.contractVersion").value("model-definition.v1"))
            .andExpect(jsonPath("$.modelKey").value(key))
            .andExpect(jsonPath("$.family").value("CATBOOST"))
            .andExpect(jsonPath("$.role").value("ALPHA"))
            .andExpect(jsonPath("$.createdBy").value("registry-admin"))
            .andExpect(jsonPath("$.definitionFingerprint").isString());

        mvc.perform(post("/api/admin/model-governance/models/" + key + "/versions")
                .with(user("registry-admin").roles("ADMIN"))
                .contentType(MediaType.APPLICATION_JSON)
                .content(versionJson("v1.0.0", HASH_A)))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.contractVersion").value("model-version.v1"))
            .andExpect(jsonPath("$.modelKey").value(key))
            .andExpect(jsonPath("$.versionLabel").value("v1.0.0"))
            .andExpect(jsonPath("$.artifactFingerprint").value(HASH_A))
            .andExpect(jsonPath("$.researchRegistryFingerprint").value(HASH_E))
            .andExpect(jsonPath("$.createdBy").value("registry-admin"))
            .andExpect(jsonPath("$.versionFingerprint").isString());

        mvc.perform(get("/api/admin/model-governance/models")
                .with(user("registry-admin").roles("ADMIN")))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$[*].modelKey", hasItem(key)));

        mvc.perform(get("/api/admin/model-governance/models/" + key + "/versions")
                .with(user("registry-admin").roles("ADMIN")))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$[0].versionLabel").value("v1.0.0"));

        Integer definitionAuditCount = jdbc.sql("""
                SELECT count(*) FROM audit_event
                 WHERE event_type = 'MODEL_DEFINITION_REGISTERED'
                   AND actor = 'registry-admin'
                   AND details ->> 'modelKey' = :modelKey
                """)
            .param("modelKey", key)
            .query(Integer.class)
            .single();
        Integer versionAuditCount = jdbc.sql("""
                SELECT count(*) FROM audit_event
                 WHERE event_type = 'MODEL_VERSION_REGISTERED'
                   AND actor = 'registry-admin'
                   AND details ->> 'modelKey' = :modelKey
                """)
            .param("modelKey", key)
            .query(Integer.class)
            .single();
        org.junit.jupiter.api.Assertions.assertEquals(1, definitionAuditCount);
        org.junit.jupiter.api.Assertions.assertEquals(1, versionAuditCount);
    }

    @Test
    void immutableIdentitiesRejectDuplicateDefinitionAndVersionLabel() throws Exception {
        String key = "lightgbm-alpha-" + UUID.randomUUID().toString().substring(0, 8);

        mvc.perform(post("/api/admin/model-governance/models")
                .with(user("admin").roles("ADMIN"))
                .contentType(MediaType.APPLICATION_JSON)
                .content(definitionJson(key)))
            .andExpect(status().isCreated());
        mvc.perform(post("/api/admin/model-governance/models")
                .with(user("admin").roles("ADMIN"))
                .contentType(MediaType.APPLICATION_JSON)
                .content(definitionJson(key)))
            .andExpect(status().isConflict());

        mvc.perform(post("/api/admin/model-governance/models/" + key + "/versions")
                .with(user("admin").roles("ADMIN"))
                .contentType(MediaType.APPLICATION_JSON)
                .content(versionJson("v1", HASH_A)))
            .andExpect(status().isCreated());
        mvc.perform(post("/api/admin/model-governance/models/" + key + "/versions")
                .with(user("admin").roles("ADMIN"))
                .contentType(MediaType.APPLICATION_JSON)
                .content(versionJson("v1", HASH_B)))
            .andExpect(status().isConflict());
    }

    @Test
    void invalidLineageFingerprintIsRejectedBeforePersistence() throws Exception {
        String key = "pytorch-alpha-" + UUID.randomUUID().toString().substring(0, 8);
        mvc.perform(post("/api/admin/model-governance/models")
                .with(user("admin").roles("ADMIN"))
                .contentType(MediaType.APPLICATION_JSON)
                .content(definitionJson(key)))
            .andExpect(status().isCreated());

        String invalid = versionJson("v1", "not-a-sha256");
        mvc.perform(post("/api/admin/model-governance/models/" + key + "/versions")
                .with(user("admin").roles("ADMIN"))
                .contentType(MediaType.APPLICATION_JSON)
                .content(invalid))
            .andExpect(status().isBadRequest());
    }

    @Test
    void viewerTraderAndLegacyOperatorCannotAccessV4Registry() throws Exception {
        for (String role : new String[]{"VIEWER", "TRADER", "OPERATOR"}) {
            mvc.perform(get("/api/admin/model-governance/models")
                    .with(user(role.toLowerCase()).roles(role)))
                .andExpect(status().isForbidden());
            mvc.perform(post("/api/admin/model-governance/models")
                    .with(user(role.toLowerCase()).roles(role))
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(definitionJson("blocked-" + role.toLowerCase())))
                .andExpect(status().isForbidden());
        }
    }

    private static String definitionJson(String key) {
        return """
            {
              "modelKey": "%s",
              "displayName": "Structured Alpha Challenger",
              "family": "CATBOOST",
              "role": "ALPHA",
              "provider": "IN_HOUSE",
              "licenseStatus": "APPROVED_COMMERCIAL",
              "description": "Immutable V4 model definition"
            }
            """.formatted(key);
    }

    private static String versionJson(String version, String artifactFingerprint) {
        return """
            {
              "versionLabel": "%s",
              "artifactFingerprint": "%s",
              "featureSchemaFingerprint": "%s",
              "trainingDatasetFingerprint": "%s",
              "trainingConfigFingerprint": "%s",
              "codeSha": "%s",
              "researchRegistryFingerprint": "%s",
              "trainingCutoff": "2026-10-31T10:00:00Z"
            }
            """.formatted(version, artifactFingerprint, HASH_B, HASH_C, HASH_D, CODE_SHA, HASH_E);
    }
}
