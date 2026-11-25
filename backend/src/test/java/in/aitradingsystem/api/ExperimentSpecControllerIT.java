package in.aitradingsystem.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;

class ExperimentSpecControllerIT extends AbstractPostgresIT {
    private static final String HASH_A = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";
    private static final String HASH_B = "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb";
    private static final String HASH_C = "cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc";
    private static final String HASH_D = "dddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddd";
    private static final String CODE_SHA = "0123456789abcdef0123456789abcdef01234567";

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired JdbcClient jdbc;

    @Test
    void adminRegistersImmutableCausalExperimentSpecWithAuditEvidence() throws Exception {
        UUID versionId = registerModelVersion();
        String request = experimentJson(versionId, 42, true, "PURGED_WALK_FORWARD", 5, 5, "0.001");

        String response = mvc.perform(post("/api/admin/model-governance/experiments")
                .with(user("experiment-admin").roles("ADMIN"))
                .contentType(MediaType.APPLICATION_JSON)
                .content(request))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.contractVersion").value("experiment-spec.v1"))
            .andExpect(jsonPath("$.modelVersionId").value(versionId.toString()))
            .andExpect(jsonPath("$.dataset.pointInTime").value(true))
            .andExpect(jsonPath("$.folds.protocol").value("PURGED_WALK_FORWARD"))
            .andExpect(jsonPath("$.canAuthorizeCapital").value(false))
            .andExpect(jsonPath("$.datasetBindingFingerprint").isString())
            .andExpect(jsonPath("$.targetBindingFingerprint").isString())
            .andExpect(jsonPath("$.foldBindingFingerprint").isString())
            .andExpect(jsonPath("$.economicCostBindingFingerprint").isString())
            .andExpect(jsonPath("$.specFingerprint").isString())
            .andReturn().getResponse().getContentAsString();

        JsonNode created = mapper.readTree(response);
        UUID experimentId = UUID.fromString(created.path("experimentId").asText());

        mvc.perform(get("/api/admin/model-governance/experiments/" + experimentId)
                .with(user("experiment-admin").roles("ADMIN")))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.specFingerprint").value(created.path("specFingerprint").asText()));

        Integer auditCount = jdbc.sql("""
                SELECT count(*) FROM audit_event
                 WHERE event_type = 'EXPERIMENT_SPEC_REGISTERED'
                   AND actor = 'experiment-admin'
                   AND entity_id = :experimentId
                   AND details ->> 'modelVersionId' = :versionId
                """)
            .param("experimentId", experimentId.toString())
            .param("versionId", versionId.toString())
            .query(Integer.class)
            .single();
        assertEquals(1, auditCount);
    }

    @Test
    void canonicalDuplicateIsRejectedButSeedChangeCreatesDistinctSpec() throws Exception {
        UUID versionId = registerModelVersion();
        String request = experimentJson(versionId, 7, true, "PURGED_WALK_FORWARD", 5, 5, "0.001");

        mvc.perform(post("/api/admin/model-governance/experiments")
                .with(user("admin").roles("ADMIN"))
                .contentType(MediaType.APPLICATION_JSON).content(request))
            .andExpect(status().isCreated());
        mvc.perform(post("/api/admin/model-governance/experiments")
                .with(user("admin").roles("ADMIN"))
                .contentType(MediaType.APPLICATION_JSON).content(request))
            .andExpect(status().isConflict());

        mvc.perform(post("/api/admin/model-governance/experiments")
                .with(user("admin").roles("ADMIN"))
                .contentType(MediaType.APPLICATION_JSON)
                .content(experimentJson(versionId, 8, true, "PURGED_WALK_FORWARD", 5, 5, "0.001")))
            .andExpect(status().isCreated());
    }

    @Test
    void nonPointInTimeInvalidFoldAndUnsafeCostsAreRejected() throws Exception {
        UUID versionId = registerModelVersion();

        mvc.perform(post("/api/admin/model-governance/experiments")
                .with(user("admin").roles("ADMIN"))
                .contentType(MediaType.APPLICATION_JSON)
                .content(experimentJson(versionId, 1, false, "PURGED_WALK_FORWARD", 5, 5, "0.001")))
            .andExpect(status().isBadRequest());

        mvc.perform(post("/api/admin/model-governance/experiments")
                .with(user("admin").roles("ADMIN"))
                .contentType(MediaType.APPLICATION_JSON)
                .content(experimentJson(versionId, 2, true, "CHRONOLOGICAL", 1, 1, "0.001")))
            .andExpect(status().isBadRequest());

        mvc.perform(post("/api/admin/model-governance/experiments")
                .with(user("admin").roles("ADMIN"))
                .contentType(MediaType.APPLICATION_JSON)
                .content(experimentJson(versionId, 3, true, "PURGED_WALK_FORWARD", 5, 5, "0.20")))
            .andExpect(status().isBadRequest());
    }

    @Test
    void disabledOrUnknownModelVersionCannotRegisterExperiment() throws Exception {
        UUID versionId = registerModelVersion();
        mvc.perform(put("/api/admin/model-governance/emergency-controls/models/" + versionId)
                .with(user("admin").roles("ADMIN"))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"disabled\":true,\"reason\":\"quarantine before experiment\"}"))
            .andExpect(status().isOk());

        mvc.perform(post("/api/admin/model-governance/experiments")
                .with(user("admin").roles("ADMIN"))
                .contentType(MediaType.APPLICATION_JSON)
                .content(experimentJson(versionId, 4, true, "PURGED_WALK_FORWARD", 5, 5, "0.001")))
            .andExpect(status().isConflict());

        mvc.perform(post("/api/admin/model-governance/experiments")
                .with(user("admin").roles("ADMIN"))
                .contentType(MediaType.APPLICATION_JSON)
                .content(experimentJson(UUID.randomUUID(), 5, true, "PURGED_WALK_FORWARD", 5, 5, "0.001")))
            .andExpect(status().isNotFound());
    }

    @Test
    void viewerTraderAndLegacyOperatorCannotAccessExperimentSpecs() throws Exception {
        for (String role : new String[]{"VIEWER", "TRADER", "OPERATOR"}) {
            mvc.perform(get("/api/admin/model-governance/experiments")
                    .with(user(role.toLowerCase()).roles(role)))
                .andExpect(status().isForbidden());
        }
    }

    private UUID registerModelVersion() throws Exception {
        String key = "experiment-model-" + UUID.randomUUID().toString().substring(0, 8);
        String family = "EXP_" + UUID.randomUUID().toString().replace("-", "").substring(0, 8).toUpperCase();
        mvc.perform(post("/api/admin/model-governance/models")
                .with(user("setup-admin").roles("ADMIN"))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "modelKey": "%s",
                      "displayName": "Experiment Test Model",
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
            .andReturn().getResponse().getContentAsString();
        return UUID.fromString(mapper.readTree(response).path("versionId").asText());
    }

    private static String experimentJson(
            UUID versionId,
            long seed,
            boolean pointInTime,
            String foldProtocol,
            int purgeBars,
            int embargoBars,
            String slippageRate) {
        return """
            {
              "modelVersionId": "%s",
              "fromAt": "2025-01-01T03:45:00Z",
              "toAt": "2025-06-30T10:00:00Z",
              "seed": %d,
              "dataset": {
                "datasetFingerprint": "%s",
                "featureSchemaFingerprint": "%s",
                "universeFingerprint": "%s",
                "asOf": "2025-06-30T10:00:00Z",
                "pointInTime": %s
              },
              "target": {
                "targetName": "NEXT_5_BAR_NET_RETURN_POSITIVE",
                "horizonBars": 5,
                "labelDefinitionFingerprint": "%s"
              },
              "folds": {
                "protocol": "%s",
                "folds": 5,
                "trainBars": 500,
                "testBars": 100,
                "purgeBars": %d,
                "embargoBars": %d
              },
              "economicCosts": {
                "brokerageRate": 0.0005,
                "slippageRate": %s,
                "spreadBps": 5,
                "statutoryCostRate": 0.001
              }
            }
            """.formatted(
                versionId, seed, HASH_A, HASH_B, HASH_C, pointInTime,
                HASH_D, foldProtocol, purgeBars, embargoBars, slippageRate);
    }
}
