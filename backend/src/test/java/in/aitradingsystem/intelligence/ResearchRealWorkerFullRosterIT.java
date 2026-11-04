package in.aitradingsystem.intelligence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import in.aitradingsystem.governance.ModelRegistryService;
import in.aitradingsystem.support.AbstractPostgresIT;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Complete synthetic Spring ADMIN -> V63 preflight -> V64 roster ->
 * V61 committed intent -> Java real HTTP/1.1 -> Python actual trained
 * CatBoost/LightGBM -> V61 immutable receipts -> V62/V56 PostgreSQL custody.
 *
 * Not a licensed-source, deployed production or broker acceptance test.
 */
@EnabledIfEnvironmentVariable(named = "ATS_V4_REAL_HTTP_SMOKE", matches = "true")
@TestPropertySource(properties = {
    "app.research.artifact-worker.enabled=true",
    "app.research.roster-dispatch.enabled=true"
})
class ResearchRealWorkerFullRosterIT extends AbstractPostgresIT {
    private static final UUID CAT = UUID.fromString(
        "11111111-1111-4111-8111-111111111111");
    private static final UUID LIGHT = UUID.fromString(
        "22222222-2222-4222-8222-222222222222");
    private static final String A = "a".repeat(64);
    private static final String B = "b".repeat(64);
    private static final String C = "c".repeat(64);
    private static final String D = "d".repeat(64);
    private static final String E = "e".repeat(64);
    private static final String ENDPOINT =
        "/api/admin/model-governance/paper-shadow";
    private static final String ACTOR = "ci-v67-admin";

    @Autowired MockMvc mvc;
    @Autowired JdbcClient jdbc;
    @Autowired ObjectMapper mapper;

    @DynamicPropertySource
    static void researchWorker(DynamicPropertyRegistry registry) {
        registry.add("app.research.artifact-worker.base-url",
            () -> System.getenv("ATS_V4_RESEARCH_SMOKE_BASE_URL"));
        registry.add("app.research.artifact-worker.token",
            () -> System.getenv("ATS_V4_RESEARCH_SMOKE_TOKEN"));
    }

    @Test
    void realWorkerScoresBothModelsAndFinalizesOnlyImmutableResearchEvidence()
            throws Exception {
        JsonNode manifest = mapper.readTree(Files.readString(
            Path.of(System.getenv("ATS_V4_RESEARCH_SMOKE_MANIFEST"))));
        if (manifest.path("models").size() != 2) {
            throw new IllegalStateException("Exactly two synthetic lab models required");
        }
        for (JsonNode model : manifest.path("models")) {
            UUID id = UUID.fromString(model.path("model_version_id").asText());
            if (!id.equals(CAT) && !id.equals(LIGHT)) {
                throw new IllegalStateException("Unexpected lab model identity");
            }
            registerVersion(id, model.path("family").asText(),
                model.path("artifact_fingerprint").asText());
        }

        UUID session = UUID.randomUUID();
        String caseKey = "ci-v67-" + UUID.randomUUID().toString().substring(0, 8);
        mvc.perform(post(ENDPOINT + "/sessions")
                .with(user(ACTOR).roles("ADMIN"))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"sessionId":"%s","targetSpecFingerprint":"%s",
                     "models":[
                       {"modelVersionId":"%s","role":"PAPER_CHAMPION"},
                       {"modelVersionId":"%s","role":"SHADOW_CHALLENGER"}]}
                    """.formatted(session, C, CAT, LIGHT)))
            .andExpect(status().isCreated());
        var enrollment = jdbc.sql("""
                SELECT created_at FROM v4_matched_session
                 WHERE session_id=:session
                """).param("session", session)
            .query((rs, row) -> rs.getObject(1, OffsetDateTime.class).toInstant())
            .single();

        // All synthetic V59/V60 snapshots are time-bound AFTER enrollment.
        // Actual market/news license and upstream custody are NOT asserted.
        // Use exact UTC seconds, because the Python/Pydantic canonical JSON
        // hash normalizes fractional seconds to microsecond precision.
        Instant captured = Instant.now().plusSeconds(2)
            .truncatedTo(java.time.temporal.ChronoUnit.SECONDS);
        if (!captured.isAfter(enrollment)) {
            throw new IllegalStateException("Invalid lab enrollment chronology");
        }
        Instant marketAt = captured.minusSeconds(2);
        Instant newsAt = captured.minusSeconds(3);
        Instant featureAsOf = captured.plusSeconds(1);
        Instant projectedAt = featureAsOf.plusSeconds(1);
        jdbc.sql("""
                INSERT INTO v4_matched_source_capture (
                    session_id, case_key, candle_id, news_id, symbol,
                    market_available_at, news_available_at,
                    market_snapshot_fingerprint, news_snapshot_fingerprint,
                    input_fingerprint, capture_fingerprint,
                    captured_at, captured_by)
                VALUES (:session, :caseKey, 1, :news, 'RELIANCE',
                    :marketAt, :newsAt, :marketHash, :newsHash,
                    :sourceHash, :captureHash, :captured, 'ci-synthetic')
                """)
            .param("session", session).param("caseKey", caseKey)
            .param("news", UUID.randomUUID())
            .param("marketAt", utc(marketAt)).param("newsAt", utc(newsAt))
            .param("marketHash", B).param("newsHash", C)
            .param("sourceHash", A).param("captureHash", D)
            .param("captured", utc(captured)).update();
        jdbc.sql("""
                INSERT INTO v4_matched_feature_projection (
                    session_id, case_key, source_capture_fingerprint,
                    previous_candle_id, news_ai_evidence_id,
                    feature_schema_version, feature_as_of,
                    latest_feature_available_at, feature_json,
                    feature_payload_fingerprint, projected_at, projected_by)
                VALUES (:session, :caseKey, :captureHash, 2, :evidence,
                    'features.market.v1', :featureAt, :latestAt,
                    CAST(:features AS jsonb), :featureHash, :projected, 'ci-synthetic')
                """)
            .param("session", session).param("caseKey", caseKey)
            .param("captureHash", D).param("evidence", UUID.randomUUID())
            .param("featureAt", utc(featureAsOf))
            .param("latestAt", utc(marketAt))
            .param("features", """
                {"market.return_1":0.01,"market.range_pct":0.016,
                 "market.volume_change_1":0.22,
                 "event.AI_NEWS_EVIDENCE.direction_score":0.70,
                 "event.AI_NEWS_EVIDENCE.confidence":0.80,
                 "event.AI_NEWS_EVIDENCE.materiality":0.75}
                """)
            .param("featureHash", E)
            .param("projected", utc(projectedAt)).update();

        // A prospective snapshot must not be used before its availability.
        while (Instant.now().isBefore(projectedAt.plusMillis(50))) {
            Thread.sleep(30);
        }

        String route = ENDPOINT + "/sessions/" + session
            + "/cases/" + caseKey + "/dispatch-research-roster";
        mvc.perform(post(route).with(user(ACTOR).roles("ADMIN")))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("FINALIZED_RESEARCH_ONLY"))
            .andExpect(jsonPath("$.expectedModels").value(2))
            .andExpect(jsonPath("$.succeeded").value(2))
            .andExpect(jsonPath("$.unresolved").value(0))
            .andExpect(jsonPath("$.matchedEvidenceFinalized").value(true))
            .andExpect(jsonPath("$.paperOrdersSubmitted").value(false))
            .andExpect(jsonPath("$.canAuthorizeCapital").value(false));

        assertEquals(2, count("v4_research_inference_reservation", session, caseKey));
        assertEquals(2, jdbc.sql("""
                SELECT count(*) FROM v4_research_inference_receipt rc
                JOIN v4_research_inference_reservation rs
                  ON rs.reservation_id=rc.reservation_id
                WHERE rs.session_id=:session AND rs.case_key=:caseKey
                  AND rc.status='SUCCEEDED'
                  AND rc.can_authorize_capital=FALSE
                """).param("session", session).param("caseKey", caseKey)
            .query(Integer.class).single());
        assertEquals(1, count("v4_research_matched_case_link", session, caseKey));
        assertEquals(1, count("v4_matched_case", session, caseKey));
        assertEquals(2, count("v4_matched_decision", session, caseKey));
        assertEquals(0, jdbc.sql("""
                SELECT count(*) FROM paper_order
                 WHERE correlation_id=:correlation
                """).param("correlation", session.toString())
            .query(Integer.class).single());
        // Every identical case/model is terminally reserved: a repeated batch
        // must not resend models or rewrite original V56/V62 observations.
        mvc.perform(post(route).with(user(ACTOR).roles("ADMIN")))
            .andExpect(status().isConflict());
        assertEquals(2, count("v4_research_inference_reservation", session, caseKey));
        assertEquals(1, count("v4_matched_case", session, caseKey));
    }

    private void registerVersion(UUID modelId, String family, String artifactHash) {
        if (!java.util.Set.of("CATBOOST", "LIGHTGBM").contains(family)
                || artifactHash == null || !artifactHash.matches("^[a-f0-9]{64}$")) {
            throw new IllegalArgumentException("Invalid synthetic trained-model identity");
        }
        UUID definitionId = UUID.randomUUID();
        String key = "ci-v67-" + family.toLowerCase() + "-"
            + definitionId.toString().substring(0, 8);
        jdbc.sql("""
                INSERT INTO model_definition (
                    definition_id, model_key, display_name, family, model_role,
                    provider, license_status, definition_fingerprint, created_by)
                VALUES (:id, :key, 'Synthetic V67 research', :family, 'ALPHA',
                    'IN_HOUSE', 'RESEARCH_ONLY', :definitionHash, 'ci-synthetic')
                """)
            .param("id", definitionId).param("key", key)
            .param("family", family)
            .param("definitionHash", ModelRegistryService.fingerprint(
                "ci-v67-definition", definitionId.toString(), family))
            .update();
        jdbc.sql("""
                INSERT INTO model_version (
                    version_id, definition_id, version_label, artifact_fingerprint,
                    feature_schema_fingerprint, training_dataset_fingerprint,
                    training_config_fingerprint, code_sha,
                    version_fingerprint, created_by)
                VALUES (:versionId, :definitionId, 'ci-trained-v1', :artifact,
                    :featureSchema, :dataset, :training, :codeSha,
                    :versionHash, 'ci-synthetic')
                """)
            .param("versionId", modelId).param("definitionId", definitionId)
            .param("artifact", artifactHash)
            .param("featureSchema", B).param("dataset", C)
            .param("training", D).param("codeSha", "0".repeat(40))
            .param("versionHash", ModelRegistryService.fingerprint(
                "ci-v67-version", modelId.toString(), artifactHash))
            .update();
    }

    private int count(String table, UUID session, String caseKey) {
        if (!java.util.Set.of(
                "v4_research_inference_reservation",
                "v4_research_matched_case_link", "v4_matched_case",
                "v4_matched_decision").contains(table)) {
            throw new IllegalArgumentException("Bounded synthetic research table only");
        }
        return jdbc.sql("SELECT count(*) FROM " + table
                + " WHERE session_id=:session AND case_key=:caseKey")
            .param("session", session).param("caseKey", caseKey)
            .query(Integer.class).single();
    }

    private static OffsetDateTime utc(Instant at) {
        return OffsetDateTime.ofInstant(at, ZoneOffset.UTC);
    }
}
