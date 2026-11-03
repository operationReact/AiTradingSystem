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
import in.aitradingsystem.governance.BacktestComparisonService;
import in.aitradingsystem.governance.BacktestComparisonService.ComparisonMetrics;
import in.aitradingsystem.support.AbstractPostgresIT;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;

class SampleSufficiencyControllerIT extends AbstractPostgresIT {
    private static final String HASH_A = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";
    private static final String HASH_B = "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb";
    private static final String HASH_C = "cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc";
    private static final String HASH_D = "dddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddd";
    private static final String HASH_F = "ffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff";
    private static final String CODE_SHA = "0123456789abcdef0123456789abcdef01234567";

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired JdbcClient jdbc;

    record CompletedResult(UUID resultId, ComparisonMetrics metrics) {}
    record ComparisonFixture(UUID comparisonId, CompletedResult first, CompletedResult second) {}

    @Test
    void insufficientPassIsDerivedAsInconclusiveAndEvaluationIsIdempotent() throws Exception {
        ComparisonFixture fixture = createComparison("PASS", 1500, "PASS", 300);

        String createdBody = evaluate(fixture.comparisonId(), 1000, true);
        JsonNode created = mapper.readTree(createdBody);
        UUID evaluationId = UUID.fromString(created.path("evaluationId").asText());
        assertEquals("INCONCLUSIVE", created.path("overallStatus").asText());
        assertEquals(false, created.path("promotionAllowed").asBoolean());
        assertEquals(false, created.path("canAuthorizeCapital").asBoolean());

        JsonNode sufficient = candidate(created, fixture.first().resultId());
        assertEquals(1500, sufficient.path("observedSampleCount").asLong());
        assertEquals(true, sufficient.path("sufficient").asBoolean());
        assertEquals(0, sufficient.path("shortfall").asLong());
        assertEquals("PASS", sufficient.path("originalAssessment").asText());
        assertEquals("PASS", sufficient.path("effectiveAssessment").asText());

        JsonNode insufficient = candidate(created, fixture.second().resultId());
        assertEquals(300, insufficient.path("observedSampleCount").asLong());
        assertEquals(false, insufficient.path("sufficient").asBoolean());
        assertEquals(700, insufficient.path("shortfall").asLong());
        assertEquals("PASS", insufficient.path("originalAssessment").asText());
        assertEquals("INCONCLUSIVE", insufficient.path("effectiveAssessment").asText());

        String duplicateBody = evaluate(fixture.comparisonId(), 1000, false);
        JsonNode duplicate = mapper.readTree(duplicateBody);
        assertEquals(evaluationId.toString(), duplicate.path("evaluationId").asText());
        assertEquals(
            created.path("evaluationFingerprint").asText(),
            duplicate.path("evaluationFingerprint").asText());

        mvc.perform(get("/api/admin/model-governance/sample-sufficiency/" + evaluationId)
                .with(user("sufficiency-admin").roles("ADMIN")))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.evaluationId").value(evaluationId.toString()))
            .andExpect(jsonPath("$.overallStatus").value("INCONCLUSIVE"));

        Integer evaluationCount = jdbc.sql("""
                SELECT count(*) FROM sample_sufficiency_evaluation
                 WHERE comparison_id = :comparisonId AND minimum_sample_count = 1000
                """)
            .param("comparisonId", fixture.comparisonId())
            .query(Integer.class)
            .single();
        assertEquals(1, evaluationCount);
        Integer auditCount = jdbc.sql("""
                SELECT count(*) FROM audit_event
                 WHERE event_type = 'SAMPLE_SUFFICIENCY_EVALUATED'
                   AND entity_id = :evaluationId
                   AND details ->> 'canAuthorizeCapital' = 'false'
                   AND details ->> 'promotionAllowed' = 'false'
                """)
            .param("evaluationId", evaluationId.toString())
            .query(Integer.class)
            .single();
        assertEquals(1, auditCount);
    }

    @Test
    void sufficientOriginalInconclusiveRemainsInconclusive() throws Exception {
        ComparisonFixture fixture = createComparison("INCONCLUSIVE", 1400, "PASS", 1500);

        JsonNode evaluation = mapper.readTree(evaluate(fixture.comparisonId(), 1000, true));
        assertEquals("INCONCLUSIVE", evaluation.path("overallStatus").asText());

        JsonNode candidate = candidate(evaluation, fixture.first().resultId());
        assertEquals(true, candidate.path("sufficient").asBoolean());
        assertEquals(0, candidate.path("shortfall").asLong());
        assertEquals("INCONCLUSIVE", candidate.path("originalAssessment").asText());
        assertEquals("INCONCLUSIVE", candidate.path("effectiveAssessment").asText());
    }

    @Test
    void sufficientPassAndFailPreserveAssessmentsAndPolicyIsVersioned() throws Exception {
        ComparisonFixture fixture = createComparison("PASS", 1600, "FAIL", 1700);

        JsonNode threshold1000 = mapper.readTree(evaluate(fixture.comparisonId(), 1000, true));
        assertEquals("SUFFICIENT", threshold1000.path("overallStatus").asText());
        assertEquals(
            candidate(threshold1000, fixture.first().resultId()).path("originalAssessment").asText(),
            candidate(threshold1000, fixture.first().resultId()).path("effectiveAssessment").asText());
        assertEquals(
            candidate(threshold1000, fixture.second().resultId()).path("originalAssessment").asText(),
            candidate(threshold1000, fixture.second().resultId()).path("effectiveAssessment").asText());

        JsonNode threshold2000 = mapper.readTree(evaluate(fixture.comparisonId(), 2000, true));
        assertEquals("INCONCLUSIVE", threshold2000.path("overallStatus").asText());
        assertNotEquals(
            threshold1000.path("policyFingerprint").asText(),
            threshold2000.path("policyFingerprint").asText());
        assertNotEquals(
            threshold1000.path("evaluationFingerprint").asText(),
            threshold2000.path("evaluationFingerprint").asText());
        assertNotEquals(
            threshold1000.path("evaluationId").asText(),
            threshold2000.path("evaluationId").asText());

        mvc.perform(get("/api/admin/model-governance/sample-sufficiency")
                .with(user("sufficiency-admin").roles("ADMIN"))
                .param("comparisonId", fixture.comparisonId().toString()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.length()").value(2))
            .andExpect(jsonPath("$[0].promotionAllowed").value(false))
            .andExpect(jsonPath("$[0].canAuthorizeCapital").value(false));
    }

    @Test
    void nonAdminRolesCannotReadOrCreateSampleSufficiencyEvidence() throws Exception {
        for (String role : new String[]{"VIEWER", "TRADER", "OPERATOR"}) {
            mvc.perform(get("/api/admin/model-governance/sample-sufficiency")
                    .with(user(role.toLowerCase()).roles(role)))
                .andExpect(status().isForbidden());
            mvc.perform(post("/api/admin/model-governance/sample-sufficiency")
                    .with(user(role.toLowerCase()).roles(role))
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"comparisonId\":\"" + UUID.randomUUID()
                        + "\",\"minimumSampleCount\":1000}"))
                .andExpect(status().isForbidden());
        }
    }

    private String evaluate(UUID comparisonId, long minimumSampleCount, boolean expectCreated)
            throws Exception {
        var result = mvc.perform(post("/api/admin/model-governance/sample-sufficiency")
                .with(user("sufficiency-admin").roles("ADMIN"))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"comparisonId":"%s","minimumSampleCount":%d}
                    """.formatted(comparisonId, minimumSampleCount)));
        if (expectCreated) result.andExpect(status().isCreated());
        else result.andExpect(status().isOk());
        return result
            .andExpect(jsonPath("$.contractVersion").value("sample-sufficiency.v1"))
            .andExpect(jsonPath("$.minimumSampleCount").value(minimumSampleCount))
            .andExpect(jsonPath("$.candidates.length()").value(2))
            .andExpect(jsonPath("$.promotionAllowed").value(false))
            .andExpect(jsonPath("$.canAuthorizeCapital").value(false))
            .andReturn().getResponse().getContentAsString();
    }

    private JsonNode candidate(JsonNode evaluation, UUID resultId) {
        for (JsonNode candidate : evaluation.path("candidates")) {
            if (resultId.toString().equals(candidate.path("resultId").asText())) return candidate;
        }
        throw new AssertionError("Candidate result not found in sufficiency evaluation: " + resultId);
    }

    private ComparisonFixture createComparison(
            String firstAssessment,
            long firstSamples,
            String secondAssessment,
            long secondSamples) throws Exception {
        String fixtureId = UUID.randomUUID().toString().substring(0, 8);
        CompletedResult first = completeResult(
            "first-" + fixtureId,
            firstAssessment,
            firstSamples,
            metricsOne());
        CompletedResult second = completeResult(
            "second-" + fixtureId,
            secondAssessment,
            secondSamples,
            metricsTwo());

        List<Map<String, Object>> candidates = List.of(
            Map.of("resultId", first.resultId().toString(), "metrics", first.metrics()),
            Map.of("resultId", second.resultId().toString(), "metrics", second.metrics()));
        String response = mvc.perform(post("/api/admin/model-governance/backtest-comparisons")
                .with(user("setup-admin").roles("ADMIN"))
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(Map.of("candidates", candidates))))
            .andExpect(status().isCreated())
            .andReturn().getResponse().getContentAsString();
        UUID comparisonId = UUID.fromString(mapper.readTree(response).path("comparisonId").asText());
        return new ComparisonFixture(comparisonId, first, second);
    }

    private CompletedResult completeResult(
            String suffix,
            String assessment,
            long sampleCount,
            ComparisonMetrics metrics) throws Exception {
        UUID versionId = registerModelVersion(suffix);
        UUID experimentId = registerExperiment(versionId);
        String runnerVersion = "sufficiency-runner-" + suffix;

        String jobBody = mvc.perform(post("/api/admin/model-governance/experiment-jobs")
                .with(user("setup-admin").roles("ADMIN"))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"experimentId":"%s","runnerType":"JAVA_BACKTEST","runnerVersion":"%s","maxAttempts":2}
                    """.formatted(experimentId, runnerVersion)))
            .andExpect(status().isCreated())
            .andReturn().getResponse().getContentAsString();
        UUID jobId = UUID.fromString(mapper.readTree(jobBody).path("jobId").asText());

        String claimBody = mvc.perform(post("/api/admin/model-governance/experiment-jobs/claim")
                .with(user("setup-admin").roles("ADMIN"))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"runnerType":"JAVA_BACKTEST","workerId":"sufficiency-worker-%s","leaseSeconds":300}
                    """.formatted(suffix)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.jobId").value(jobId.toString()))
            .andReturn().getResponse().getContentAsString();
        UUID claimToken = UUID.fromString(mapper.readTree(claimBody).path("claimToken").asText());

        mvc.perform(post("/api/admin/model-governance/experiment-jobs/" + jobId + "/start")
                .with(user("setup-admin").roles("ADMIN"))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"claimToken\":\"" + claimToken + "\"}"))
            .andExpect(status().isOk());

        String metricsFingerprint = BacktestComparisonService.metricsFingerprint(metrics);
        String resultBody = mvc.perform(post("/api/admin/model-governance/experiment-jobs/" + jobId + "/result")
                .with(user("setup-admin").roles("ADMIN"))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "claimToken":"%s","assessment":"%s","sampleCount":%d,
                      "resultArtifactFingerprint":"%s","metricsFingerprint":"%s","evidenceFingerprint":"%s"
                    }
                    """.formatted(claimToken, assessment, sampleCount, HASH_D, metricsFingerprint, HASH_F)))
            .andExpect(status().isCreated())
            .andReturn().getResponse().getContentAsString();
        return new CompletedResult(
            UUID.fromString(mapper.readTree(resultBody).path("resultId").asText()),
            metrics);
    }

    private UUID registerModelVersion(String suffix) throws Exception {
        String normalized = suffix.replace("-", "");
        String key = "sufficiency-" + suffix;
        String family = "SUF_" + normalized.toUpperCase();
        mvc.perform(post("/api/admin/model-governance/models")
                .with(user("setup-admin").roles("ADMIN"))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "modelKey":"%s","displayName":"Sufficiency %s","family":"%s",
                      "role":"ALPHA","provider":"IN_HOUSE","licenseStatus":"APPROVED_COMMERCIAL"
                    }
                    """.formatted(key, suffix, family)))
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

    private UUID registerExperiment(UUID versionId) throws Exception {
        String response = mvc.perform(post("/api/admin/model-governance/experiments")
                .with(user("setup-admin").roles("ADMIN"))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "modelVersionId":"%s","fromAt":"2025-01-01T03:45:00Z","toAt":"2025-06-30T10:00:00Z","seed":42,
                      "dataset":{"datasetFingerprint":"%s","featureSchemaFingerprint":"%s","universeFingerprint":"%s","asOf":"2025-06-30T10:00:00Z","pointInTime":true},
                      "target":{"targetName":"NEXT_5_BAR_NET_RETURN_POSITIVE","horizonBars":5,"labelDefinitionFingerprint":"%s"},
                      "folds":{"protocol":"PURGED_WALK_FORWARD","folds":5,"trainBars":500,"testBars":100,"purgeBars":5,"embargoBars":5},
                      "economicCosts":{"brokerageRate":0.0005,"slippageRate":0.001,"spreadBps":5,"statutoryCostRate":0.001}
                    }
                    """.formatted(versionId, HASH_A, HASH_B, HASH_C, HASH_D)))
            .andExpect(status().isCreated())
            .andReturn().getResponse().getContentAsString();
        return UUID.fromString(mapper.readTree(response).path("experimentId").asText());
    }

    private static ComparisonMetrics metricsOne() {
        return new ComparisonMetrics(
            new BigDecimal("12500.00"), new BigDecimal("12.50"), new BigDecimal("1.45"),
            new BigDecimal("1.90"), new BigDecimal("6.20"), new BigDecimal("58.50"),
            new BigDecimal("1.80"), new BigDecimal("220.50"), new BigDecimal("350000"),
            new BigDecimal("1250"), new BigDecimal("0.18"), new BigDecimal("0.54"),
            new BigDecimal("0.04"), new BigDecimal("0.63"), new BigDecimal("0.08"),
            new BigDecimal("0.42"));
    }

    private static ComparisonMetrics metricsTwo() {
        return new ComparisonMetrics(
            new BigDecimal("11200.00"), new BigDecimal("11.20"), new BigDecimal("1.31"),
            new BigDecimal("1.72"), new BigDecimal("5.80"), new BigDecimal("56.20"),
            new BigDecimal("1.67"), new BigDecimal("195.00"), new BigDecimal("330000"),
            new BigDecimal("1180"), new BigDecimal("0.20"), new BigDecimal("0.57"),
            new BigDecimal("0.05"), new BigDecimal("0.60"), new BigDecimal("0.07"),
            new BigDecimal("0.39"));
    }
}
