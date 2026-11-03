package in.aitradingsystem.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
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

class ExperimentJobControllerIT extends AbstractPostgresIT {
    private static final String HASH_A = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";
    private static final String HASH_B = "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb";
    private static final String HASH_C = "cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc";
    private static final String HASH_D = "dddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddd";
    private static final String HASH_E = "eeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeee";
    private static final String HASH_F = "ffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff";
    private static final String CODE_SHA = "0123456789abcdef0123456789abcdef01234567";

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired JdbcClient jdbc;

    @Test
    void deterministicJobCanBeClaimedStartedAndCompletedIntoImmutableResult() throws Exception {
        UUID experimentId = registerExperiment(42);

        String queuedBody = mvc.perform(post("/api/admin/model-governance/experiment-jobs")
                .with(user("orchestration-admin").roles("ADMIN"))
                .contentType(MediaType.APPLICATION_JSON)
                .content(enqueueJson(experimentId, "JAVA_BACKTEST", "java-backtest.v4", 3)))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.contractVersion").value("experiment-job.v1"))
            .andExpect(jsonPath("$.status").value("QUEUED"))
            .andExpect(jsonPath("$.attempt").value(0))
            .andExpect(jsonPath("$.canAuthorizeCapital").value(false))
            .andExpect(jsonPath("$.orchestrationFingerprint").isString())
            .andReturn().getResponse().getContentAsString();
        JsonNode queued = mapper.readTree(queuedBody);
        UUID jobId = UUID.fromString(queued.path("jobId").asText());

        String duplicateBody = mvc.perform(post("/api/admin/model-governance/experiment-jobs")
                .with(user("orchestration-admin").roles("ADMIN"))
                .contentType(MediaType.APPLICATION_JSON)
                .content(enqueueJson(experimentId, "JAVA_BACKTEST", "java-backtest.v4", 3)))
            .andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString();
        assertEquals(jobId.toString(), mapper.readTree(duplicateBody).path("jobId").asText());
        assertEquals(
            queued.path("orchestrationFingerprint").asText(),
            mapper.readTree(duplicateBody).path("orchestrationFingerprint").asText());

        String claimedBody = mvc.perform(post("/api/admin/model-governance/experiment-jobs/claim")
                .with(user("worker-admin").roles("ADMIN"))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"runnerType":"JAVA_BACKTEST","workerId":"backtest-worker-01","leaseSeconds":300}
                    """))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.jobId").value(jobId.toString()))
            .andExpect(jsonPath("$.status").value("CLAIMED"))
            .andExpect(jsonPath("$.attempt").value(1))
            .andExpect(jsonPath("$.claimToken").isString())
            .andReturn().getResponse().getContentAsString();
        UUID claimToken = UUID.fromString(mapper.readTree(claimedBody).path("claimToken").asText());

        mvc.perform(post("/api/admin/model-governance/experiment-jobs/" + jobId + "/start")
                .with(user("worker-admin").roles("ADMIN"))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"claimToken\":\"" + claimToken + "\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("RUNNING"));

        String resultBody = mvc.perform(post("/api/admin/model-governance/experiment-jobs/" + jobId + "/result")
                .with(user("worker-admin").roles("ADMIN"))
                .contentType(MediaType.APPLICATION_JSON)
                .content(resultJson(claimToken, "PASS", 1200)))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.contractVersion").value("experiment-result.v1"))
            .andExpect(jsonPath("$.jobId").value(jobId.toString()))
            .andExpect(jsonPath("$.experimentId").value(experimentId.toString()))
            .andExpect(jsonPath("$.assessment").value("PASS"))
            .andExpect(jsonPath("$.sampleCount").value(1200))
            .andExpect(jsonPath("$.resultFingerprint").isString())
            .andExpect(jsonPath("$.canAuthorizeCapital").value(false))
            .andReturn().getResponse().getContentAsString();
        JsonNode result = mapper.readTree(resultBody);

        mvc.perform(get("/api/admin/model-governance/experiment-jobs/" + jobId)
                .with(user("orchestration-admin").roles("ADMIN")))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("SUCCEEDED"));
        mvc.perform(get("/api/admin/model-governance/experiment-jobs/" + jobId + "/result")
                .with(user("orchestration-admin").roles("ADMIN")))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.resultFingerprint").value(result.path("resultFingerprint").asText()));

        mvc.perform(post("/api/admin/model-governance/experiment-jobs/" + jobId + "/result")
                .with(user("worker-admin").roles("ADMIN"))
                .contentType(MediaType.APPLICATION_JSON)
                .content(resultJson(claimToken, "PASS", 1200)))
            .andExpect(status().isConflict());

        Integer resultCount = jdbc.sql("SELECT count(*) FROM experiment_result WHERE job_id = :jobId")
            .param("jobId", jobId)
            .query(Integer.class).single();
        assertEquals(1, resultCount);
        Integer auditCount = jdbc.sql("""
                SELECT count(*) FROM audit_event
                 WHERE event_type = 'EXPERIMENT_RESULT_REGISTERED'
                   AND details ->> 'jobId' = :jobId
                   AND details ->> 'canAuthorizeCapital' = 'false'
                """)
            .param("jobId", jobId.toString())
            .query(Integer.class).single();
        assertEquals(1, auditCount);
    }

    @Test
    void retryableFailureRequeuesAndNewClaimGetsNewToken() throws Exception {
        UUID experimentId = registerExperiment(7);
        String queuedBody = mvc.perform(post("/api/admin/model-governance/experiment-jobs")
                .with(user("admin").roles("ADMIN"))
                .contentType(MediaType.APPLICATION_JSON)
                .content(enqueueJson(experimentId, "PYTHON_INTELLIGENCE", "python-intelligence.v4", 2)))
            .andExpect(status().isCreated())
            .andReturn().getResponse().getContentAsString();
        UUID jobId = UUID.fromString(mapper.readTree(queuedBody).path("jobId").asText());

        UUID firstToken = claim("PYTHON_INTELLIGENCE", "python-worker-01", jobId, 1);
        mvc.perform(post("/api/admin/model-governance/experiment-jobs/" + jobId + "/fail")
                .with(user("worker-admin").roles("ADMIN"))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"claimToken":"%s","failureCode":"TRANSIENT_IO","failureMessage":"temporary source failure","retryable":true}
                    """.formatted(firstToken)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("QUEUED"))
            .andExpect(jsonPath("$.attempt").value(1));

        UUID secondToken = claim("PYTHON_INTELLIGENCE", "python-worker-02", jobId, 2);
        assertNotEquals(firstToken, secondToken);

        mvc.perform(post("/api/admin/model-governance/experiment-jobs/" + jobId + "/fail")
                .with(user("worker-admin").roles("ADMIN"))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"claimToken":"%s","failureCode":"MODEL_ERROR","failureMessage":"second attempt failed","retryable":true}
                    """.formatted(secondToken)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("FAILED"))
            .andExpect(jsonPath("$.attempt").value(2));
    }

    @Test
    void wrongClaimTokenCannotStartOrRegisterResult() throws Exception {
        UUID experimentId = registerExperiment(99);
        String queuedBody = mvc.perform(post("/api/admin/model-governance/experiment-jobs")
                .with(user("admin").roles("ADMIN"))
                .contentType(MediaType.APPLICATION_JSON)
                .content(enqueueJson(experimentId, "JAVA_BACKTEST", "java-backtest.v4-token", 1)))
            .andExpect(status().isCreated())
            .andReturn().getResponse().getContentAsString();
        UUID jobId = UUID.fromString(mapper.readTree(queuedBody).path("jobId").asText());
        claim("JAVA_BACKTEST", "backtest-worker-token", jobId, 1);

        mvc.perform(post("/api/admin/model-governance/experiment-jobs/" + jobId + "/start")
                .with(user("worker-admin").roles("ADMIN"))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"claimToken\":\"" + UUID.randomUUID() + "\"}"))
            .andExpect(status().isConflict());
    }

    @Test
    void nonAdminRolesCannotAccessExperimentOrchestration() throws Exception {
        for (String role : new String[]{"VIEWER", "TRADER", "OPERATOR"}) {
            mvc.perform(get("/api/admin/model-governance/experiment-jobs")
                    .with(user(role.toLowerCase()).roles(role)))
                .andExpect(status().isForbidden());
            mvc.perform(get("/api/admin/model-governance/experiment-jobs/results")
                    .with(user(role.toLowerCase()).roles(role)))
                .andExpect(status().isForbidden());
        }
    }

    private UUID claim(String runnerType, String workerId, UUID expectedJobId, int expectedAttempt) throws Exception {
        String claimed = mvc.perform(post("/api/admin/model-governance/experiment-jobs/claim")
                .with(user("worker-admin").roles("ADMIN"))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"runnerType":"%s","workerId":"%s","leaseSeconds":300}
                    """.formatted(runnerType, workerId)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.jobId").value(expectedJobId.toString()))
            .andExpect(jsonPath("$.attempt").value(expectedAttempt))
            .andReturn().getResponse().getContentAsString();
        return UUID.fromString(mapper.readTree(claimed).path("claimToken").asText());
    }

    private UUID registerExperiment(long seed) throws Exception {
        UUID versionId = registerModelVersion();
        String response = mvc.perform(post("/api/admin/model-governance/experiments")
                .with(user("setup-admin").roles("ADMIN"))
                .contentType(MediaType.APPLICATION_JSON)
                .content(experimentJson(versionId, seed)))
            .andExpect(status().isCreated())
            .andReturn().getResponse().getContentAsString();
        return UUID.fromString(mapper.readTree(response).path("experimentId").asText());
    }

    private UUID registerModelVersion() throws Exception {
        String key = "job-model-" + UUID.randomUUID().toString().substring(0, 8);
        String family = "JOB_" + UUID.randomUUID().toString().replace("-", "").substring(0, 8).toUpperCase();
        mvc.perform(post("/api/admin/model-governance/models")
                .with(user("setup-admin").roles("ADMIN"))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "modelKey":"%s","displayName":"Job Test Model","family":"%s",
                      "role":"ALPHA","provider":"IN_HOUSE","licenseStatus":"APPROVED_COMMERCIAL"
                    }
                    """.formatted(key, family)))
            .andExpect(status().isCreated());
        String response = mvc.perform(post("/api/admin/model-governance/models/" + key + "/versions")
                .with(user("setup-admin").roles("ADMIN"))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "versionLabel":"v1","artifactFingerprint":"%s",
                      "featureSchemaFingerprint":"%s","trainingDatasetFingerprint":"%s",
                      "trainingConfigFingerprint":"%s","codeSha":"%s"
                    }
                    """.formatted(HASH_A, HASH_B, HASH_C, HASH_D, CODE_SHA)))
            .andExpect(status().isCreated())
            .andReturn().getResponse().getContentAsString();
        return UUID.fromString(mapper.readTree(response).path("versionId").asText());
    }

    private static String enqueueJson(UUID experimentId, String runnerType, String runnerVersion, int maxAttempts) {
        return """
            {"experimentId":"%s","runnerType":"%s","runnerVersion":"%s","maxAttempts":%d}
            """.formatted(experimentId, runnerType, runnerVersion, maxAttempts);
    }

    private static String resultJson(UUID claimToken, String assessment, long sampleCount) {
        return """
            {
              "claimToken":"%s","assessment":"%s","sampleCount":%d,
              "resultArtifactFingerprint":"%s","metricsFingerprint":"%s","evidenceFingerprint":"%s"
            }
            """.formatted(claimToken, assessment, sampleCount, HASH_D, HASH_E, HASH_F);
    }

    private static String experimentJson(UUID versionId, long seed) {
        return """
            {
              "modelVersionId":"%s","fromAt":"2025-01-01T03:45:00Z","toAt":"2025-06-30T10:00:00Z","seed":%d,
              "dataset":{"datasetFingerprint":"%s","featureSchemaFingerprint":"%s","universeFingerprint":"%s","asOf":"2025-06-30T10:00:00Z","pointInTime":true},
              "target":{"targetName":"NEXT_5_BAR_NET_RETURN_POSITIVE","horizonBars":5,"labelDefinitionFingerprint":"%s"},
              "folds":{"protocol":"PURGED_WALK_FORWARD","folds":5,"trainBars":500,"testBars":100,"purgeBars":5,"embargoBars":5},
              "economicCosts":{"brokerageRate":0.0005,"slippageRate":0.001,"spreadBps":5,"statutoryCostRate":0.001}
            }
            """.formatted(versionId, seed, HASH_A, HASH_B, HASH_C, HASH_D);
    }
}
