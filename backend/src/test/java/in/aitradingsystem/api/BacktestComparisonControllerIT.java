package in.aitradingsystem.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
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

class BacktestComparisonControllerIT extends AbstractPostgresIT {
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

    @Test
    void registersImmutableFairComparisonAndIsOrderIndependent() throws Exception {
        CompletedResult first = completeResult("A", 42, metricsOne());
        CompletedResult second = completeResult("B", 42, metricsTwo());

        String createdBody = mvc.perform(post("/api/admin/model-governance/backtest-comparisons")
                .with(user("comparison-admin").roles("ADMIN"))
                .contentType(MediaType.APPLICATION_JSON)
                .content(comparisonJson(List.of(first, second))))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.contractVersion").value("backtest-comparison.v1"))
            .andExpect(jsonPath("$.candidates.length()").value(2))
            .andExpect(jsonPath("$.datasetBindingFingerprint").isString())
            .andExpect(jsonPath("$.targetBindingFingerprint").isString())
            .andExpect(jsonPath("$.foldBindingFingerprint").isString())
            .andExpect(jsonPath("$.economicCostBindingFingerprint").isString())
            .andExpect(jsonPath("$.comparisonFingerprint").isString())
            .andExpect(jsonPath("$.promotionAllowed").value(false))
            .andExpect(jsonPath("$.canAuthorizeCapital").value(false))
            .andReturn().getResponse().getContentAsString();
        JsonNode created = mapper.readTree(createdBody);
        UUID comparisonId = UUID.fromString(created.path("comparisonId").asText());

        String reversedBody = mvc.perform(post("/api/admin/model-governance/backtest-comparisons")
                .with(user("comparison-admin").roles("ADMIN"))
                .contentType(MediaType.APPLICATION_JSON)
                .content(comparisonJson(List.of(second, first))))
            .andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString();
        JsonNode reversed = mapper.readTree(reversedBody);
        assertEquals(comparisonId.toString(), reversed.path("comparisonId").asText());
        assertEquals(created.path("comparisonFingerprint").asText(), reversed.path("comparisonFingerprint").asText());

        mvc.perform(get("/api/admin/model-governance/backtest-comparisons/" + comparisonId)
                .with(user("comparison-admin").roles("ADMIN")))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.comparisonId").value(comparisonId.toString()))
            .andExpect(jsonPath("$.candidates[0].metrics.netPnl").isNumber())
            .andExpect(jsonPath("$.candidates[0].metrics.brierScore").isNumber());

        mvc.perform(get("/api/admin/model-governance/backtest-comparisons")
                .with(user("comparison-admin").roles("ADMIN")))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$[0].candidateCount").value(2))
            .andExpect(jsonPath("$[0].canAuthorizeCapital").value(false));

        Integer comparisonCount = jdbc.sql("SELECT count(*) FROM backtest_comparison WHERE comparison_id = :id")
            .param("id", comparisonId)
            .query(Integer.class)
            .single();
        assertEquals(1, comparisonCount);
        Integer candidateCount = jdbc.sql("SELECT count(*) FROM backtest_comparison_candidate WHERE comparison_id = :id")
            .param("id", comparisonId)
            .query(Integer.class)
            .single();
        assertEquals(2, candidateCount);
        Integer auditCount = jdbc.sql("""
                SELECT count(*) FROM audit_event
                 WHERE event_type = 'BACKTEST_COMPARISON_REGISTERED'
                   AND entity_id = :comparisonId
                   AND details ->> 'promotionAllowed' = 'false'
                   AND details ->> 'canAuthorizeCapital' = 'false'
                """)
            .param("comparisonId", comparisonId.toString())
            .query(Integer.class)
            .single();
        assertEquals(1, auditCount);
    }

    @Test
    void rejectsMetricsThatDoNotMatchImmutableResultEvidence() throws Exception {
        CompletedResult first = completeResult("T1", 42, metricsOne());
        CompletedResult second = completeResult("T2", 42, metricsTwo());
        ComparisonMetrics tampered = new ComparisonMetrics(
            new BigDecimal("999999"),
            first.metrics().totalReturnPercent(),
            first.metrics().sharpeRatio(),
            first.metrics().sortinoRatio(),
            first.metrics().maxDrawdownPercent(),
            first.metrics().winRatePercent(),
            first.metrics().profitFactor(),
            first.metrics().expectancy(),
            first.metrics().turnover(),
            first.metrics().transactionCosts(),
            first.metrics().brierScore(),
            first.metrics().logLoss(),
            first.metrics().expectedCalibrationError(),
            first.metrics().precisionAtK(),
            first.metrics().netReturnAtK(),
            first.metrics().coverageRate());

        mvc.perform(post("/api/admin/model-governance/backtest-comparisons")
                .with(user("comparison-admin").roles("ADMIN"))
                .contentType(MediaType.APPLICATION_JSON)
                .content(comparisonJson(List.of(
                    new CompletedResult(first.resultId(), tampered), second))))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.containsString("do not match immutable experiment-result")));
    }

    @Test
    void rejectsCandidatesFromDifferentCausalCohorts() throws Exception {
        CompletedResult first = completeResult("C1", 42, metricsOne());
        CompletedResult differentSeed = completeResult("C2", 43, metricsTwo());

        mvc.perform(post("/api/admin/model-governance/backtest-comparisons")
                .with(user("comparison-admin").roles("ADMIN"))
                .contentType(MediaType.APPLICATION_JSON)
                .content(comparisonJson(List.of(first, differentSeed))))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.containsString("exact window, seed, dataset, target, folds and economic-cost bindings")));
    }

    @Test
    void nonAdminRolesCannotReadOrRegisterComparisons() throws Exception {
        for (String role : new String[]{"VIEWER", "TRADER", "OPERATOR"}) {
            mvc.perform(get("/api/admin/model-governance/backtest-comparisons")
                    .with(user(role.toLowerCase()).roles(role)))
                .andExpect(status().isForbidden());
            mvc.perform(post("/api/admin/model-governance/backtest-comparisons")
                    .with(user(role.toLowerCase()).roles(role))
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"candidates\":[]}"))
                .andExpect(status().isForbidden());
        }
    }

    private CompletedResult completeResult(String suffix, long seed, ComparisonMetrics metrics) throws Exception {
        UUID versionId = registerModelVersion(suffix);
        UUID experimentId = registerExperiment(versionId, seed);

        String jobBody = mvc.perform(post("/api/admin/model-governance/experiment-jobs")
                .with(user("setup-admin").roles("ADMIN"))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"experimentId":"%s","runnerType":"JAVA_BACKTEST","runnerVersion":"comparison-runner.v1","maxAttempts":2}
                    """.formatted(experimentId)))
            .andExpect(status().isCreated())
            .andReturn().getResponse().getContentAsString();
        UUID jobId = UUID.fromString(mapper.readTree(jobBody).path("jobId").asText());

        String claimBody = mvc.perform(post("/api/admin/model-governance/experiment-jobs/claim")
                .with(user("setup-admin").roles("ADMIN"))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"runnerType":"JAVA_BACKTEST","workerId":"comparison-worker-%s","leaseSeconds":300}
                    """.formatted(suffix.toLowerCase())))
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
                      "claimToken":"%s","assessment":"PASS","sampleCount":1200,
                      "resultArtifactFingerprint":"%s","metricsFingerprint":"%s","evidenceFingerprint":"%s"
                    }
                    """.formatted(claimToken, HASH_D, metricsFingerprint, HASH_F)))
            .andExpect(status().isCreated())
            .andReturn().getResponse().getContentAsString();
        UUID resultId = UUID.fromString(mapper.readTree(resultBody).path("resultId").asText());
        return new CompletedResult(resultId, metrics);
    }

    private UUID registerModelVersion(String suffix) throws Exception {
        String key = "comparison-" + suffix.toLowerCase() + "-" + UUID.randomUUID().toString().substring(0, 8);
        String family = "CMP_" + suffix.toUpperCase() + "_" + UUID.randomUUID().toString().replace("-", "").substring(0, 6).toUpperCase();
        mvc.perform(post("/api/admin/model-governance/models")
                .with(user("setup-admin").roles("ADMIN"))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "modelKey":"%s","displayName":"Comparison %s","family":"%s",
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

    private UUID registerExperiment(UUID versionId, long seed) throws Exception {
        String response = mvc.perform(post("/api/admin/model-governance/experiments")
                .with(user("setup-admin").roles("ADMIN"))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "modelVersionId":"%s","fromAt":"2025-01-01T03:45:00Z","toAt":"2025-06-30T10:00:00Z","seed":%d,
                      "dataset":{"datasetFingerprint":"%s","featureSchemaFingerprint":"%s","universeFingerprint":"%s","asOf":"2025-06-30T10:00:00Z","pointInTime":true},
                      "target":{"targetName":"NEXT_5_BAR_NET_RETURN_POSITIVE","horizonBars":5,"labelDefinitionFingerprint":"%s"},
                      "folds":{"protocol":"PURGED_WALK_FORWARD","folds":5,"trainBars":500,"testBars":100,"purgeBars":5,"embargoBars":5},
                      "economicCosts":{"brokerageRate":0.0005,"slippageRate":0.001,"spreadBps":5,"statutoryCostRate":0.001}
                    }
                    """.formatted(versionId, seed, HASH_A, HASH_B, HASH_C, HASH_D)))
            .andExpect(status().isCreated())
            .andReturn().getResponse().getContentAsString();
        return UUID.fromString(mapper.readTree(response).path("experimentId").asText());
    }

    private String comparisonJson(List<CompletedResult> candidates) throws Exception {
        List<Map<String, Object>> candidatePayloads = candidates.stream()
            .map(candidate -> Map.<String, Object>of(
                "resultId", candidate.resultId().toString(),
                "metrics", candidate.metrics()))
            .toList();
        return mapper.writeValueAsString(Map.of("candidates", candidatePayloads));
    }

    private static ComparisonMetrics metricsOne() {
        return new ComparisonMetrics(
            new BigDecimal("12500.00"),
            new BigDecimal("12.50"),
            new BigDecimal("1.45"),
            new BigDecimal("1.90"),
            new BigDecimal("6.20"),
            new BigDecimal("58.50"),
            new BigDecimal("1.80"),
            new BigDecimal("220.50"),
            new BigDecimal("350000"),
            new BigDecimal("1250"),
            new BigDecimal("0.18"),
            new BigDecimal("0.54"),
            new BigDecimal("0.04"),
            new BigDecimal("0.63"),
            new BigDecimal("0.08"),
            new BigDecimal("0.42"));
    }

    private static ComparisonMetrics metricsTwo() {
        return new ComparisonMetrics(
            new BigDecimal("11200.00"),
            new BigDecimal("11.20"),
            new BigDecimal("1.31"),
            new BigDecimal("1.72"),
            new BigDecimal("5.80"),
            new BigDecimal("56.20"),
            new BigDecimal("1.67"),
            new BigDecimal("195.00"),
            new BigDecimal("330000"),
            new BigDecimal("1180"),
            new BigDecimal("0.20"),
            new BigDecimal("0.57"),
            new BigDecimal("0.05"),
            new BigDecimal("0.60"),
            new BigDecimal("0.07"),
            new BigDecimal("0.39"));
    }
}
