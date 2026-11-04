package in.aitradingsystem.api;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import in.aitradingsystem.intelligence.ResearchInferenceAttemptLedger;
import in.aitradingsystem.intelligence.ResearchInferenceAttemptLedger.FailureCode;
import in.aitradingsystem.intelligence.ResearchInferenceAttemptLedger.Status;
import in.aitradingsystem.support.AbstractPostgresIT;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;

/** ADMIN-only V63 diagnostics against real V61/V62 Postgres tables. */
class ResearchReceiptDiagnosticsControllerIT extends AbstractPostgresIT {
    private static final String API = "/api/admin/model-governance/paper-shadow";
    private static final String A = "a".repeat(64);
    private static final String B = "b".repeat(64);
    private static final String C = "c".repeat(64);
    private static final String D = "d".repeat(64);
    private static final String E = "e".repeat(64);
    private static final String CODE = "0123456789abcdef0123456789abcdef01234567";
    @Autowired MockMvc mvc;
    @Autowired JdbcClient jdbc;
    @Autowired ObjectMapper mapper;
    @Autowired ResearchInferenceAttemptLedger ledger;

    record Fixture(UUID session, String key, UUID champion) {
        String url() {
            return API + "/sessions/" + session + "/cases/" + key
                + "/research-receipts";
        }
    }

    @BeforeEach
    void resetEmergency() throws Exception {
        mvc.perform(put("/api/admin/model-governance/emergency-controls/static-fallback")
            .with(user("v63-admin").roles("ADMIN"))
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"enabled\":false,\"reason\":\"v63 test isolation\"}"))
            .andExpect(status().isOk());
    }

    @Test
    void reportsMissingAndUnknownWithoutClaimingModelApproval() throws Exception {
        Fixture f = fixture();
        mvc.perform(get(f.url()).with(user("v63-admin").roles("ADMIN")))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.contractVersion")
                .value("v4-research-receipt-diagnostics.v1"))
            .andExpect(jsonPath("$.sourceCaptured").value(true))
            .andExpect(jsonPath("$.featuresFrozen").value(true))
            .andExpect(jsonPath("$.matchedCaseFinalized").value(false))
            .andExpect(jsonPath("$.expectedModels").value(2))
            .andExpect(jsonPath("$.successfulReceipts").value(0))
            .andExpect(jsonPath("$.unresolvedModels").value(2))
            .andExpect(jsonPath("$.models[0].status").value("NOT_ATTEMPTED"))
            .andExpect(jsonPath("$.canAuthorizeCapital").value(false))
            .andExpect(jsonPath("$.independentlyVerifiedRuntime").value(false))
            .andExpect(jsonPath("$.liveExecutionEnabled").value(false));

        var reservation = ledger.reserve(f.session(), f.key(), f.champion());
        ledger.fail(reservation.reservationId(), Status.UNKNOWN,
            FailureCode.TRANSPORT_UNKNOWN, null);
        mvc.perform(get(f.url()).with(user("v63-admin").roles("ADMIN")))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.models[?(@.modelVersionId=='"
                + f.champion() + "')].status")
                .value(org.hamcrest.Matchers.hasItem("UNKNOWN")))
            .andExpect(jsonPath("$.unresolvedModels").value(2))
            .andExpect(jsonPath("$.matchedCaseFinalized").value(false));
    }

    @Test
    void noAdminNoCaseAndPauseKeepReadOnlySafetyBoundary() throws Exception {
        Fixture f = fixture();
        for (String role : new String[]{"TRADER", "VIEWER", "OPERATOR"}) {
            mvc.perform(get(f.url()).with(user("v63-user").roles(role)))
                .andExpect(status().isForbidden());
        }
        mvc.perform(get(API + "/sessions/" + UUID.randomUUID()
                + "/cases/CASE-001/research-receipts")
                .with(user("v63-admin").roles("ADMIN")))
            .andExpect(status().isNotFound());
        mvc.perform(put(API + "/sessions/" + f.session() + "/control")
                .with(user("v63-admin").roles("ADMIN"))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"action\":\"PAUSE\",\"reason\":\"v63 research intake pause\"}"))
            .andExpect(status().isOk());
        mvc.perform(get(f.url()).with(user("v63-admin").roles("ADMIN")))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.sessionSafetyState").value("PAUSED"))
            .andExpect(jsonPath("$.sessionSafetyRevision").value(1))
            .andExpect(jsonPath("$.canAuthorizeCapital").value(false));
    }

    private Fixture fixture() throws Exception {
        UUID champion = register("CATBOOST");
        UUID challenger = register("LIGHTGBM");
        UUID session = UUID.randomUUID();
        mvc.perform(post(API + "/sessions")
            .with(user("v63-admin").roles("ADMIN"))
            .contentType(MediaType.APPLICATION_JSON)
            .content("""
                {"sessionId":"%s","targetSpecFingerprint":"%s",
                 "models":[
                  {"modelVersionId":"%s","role":"PAPER_CHAMPION"},
                  {"modelVersionId":"%s","role":"SHADOW_CHALLENGER"}]}
                """.formatted(session, C, champion, challenger)))
            .andExpect(status().isCreated());
        String key = "case-v63-" + UUID.randomUUID().toString().substring(0, 8);
        Instant captured = Instant.now().minusSeconds(20);
        jdbc.sql("""
            INSERT INTO v4_matched_source_capture (
                session_id, case_key, candle_id, news_id, symbol,
                market_available_at, news_available_at,
                market_snapshot_fingerprint, news_snapshot_fingerprint,
                input_fingerprint, capture_fingerprint, captured_at, captured_by)
            VALUES (:session, :key, 1, :news, 'RELIANCE', :market, :newsAt,
                :marketHash, :newsHash, :inputHash, :captureHash, :capturedAt, 'v63-test')
            """).param("session", session).param("key", key)
            .param("news", UUID.randomUUID())
            .param("market", utc(captured.minusSeconds(5)))
            .param("newsAt", utc(captured.minusSeconds(5)))
            .param("marketHash", B).param("newsHash", C)
            .param("inputHash", A).param("captureHash", D)
            .param("capturedAt", utc(captured)).update();
        jdbc.sql("""
            INSERT INTO v4_matched_feature_projection (
                session_id, case_key, source_capture_fingerprint,
                previous_candle_id, news_ai_evidence_id, feature_schema_version,
                feature_as_of, latest_feature_available_at,
                feature_json, feature_payload_fingerprint, projected_at, projected_by)
            VALUES (:session, :key, :captureHash, 2, :news,
                'features.market.v1', :asOf, :available,
                CAST(:features AS jsonb), :featureHash, :projected, 'v63-test')
            """).param("session", session).param("key", key)
            .param("captureHash", D).param("news", UUID.randomUUID())
            .param("asOf", utc(captured.plusSeconds(1)))
            .param("available", utc(captured))
            .param("features", "{\"market.return_1\":0.1}")
            .param("featureHash", E).param("projected", utc(captured.plusSeconds(2)))
            .update();
        return new Fixture(session, key, champion);
    }

    private UUID register(String family) throws Exception {
        String name = "v63-" + family.toLowerCase() + "-"
            + UUID.randomUUID().toString().substring(0, 8);
        mvc.perform(post("/api/admin/model-governance/models")
            .with(user("v63-admin").roles("ADMIN"))
            .contentType(MediaType.APPLICATION_JSON)
            .content("""
                {"modelKey":"%s","displayName":"V63 Readonly",
                 "family":"%s","role":"ALPHA","provider":"IN_HOUSE",
                 "licenseStatus":"RESEARCH_ONLY"}
                """.formatted(name, family)))
            .andExpect(status().isCreated());
        String body = mvc.perform(post(
                "/api/admin/model-governance/models/" + name + "/versions")
            .with(user("v63-admin").roles("ADMIN"))
            .contentType(MediaType.APPLICATION_JSON)
            .content("""
                {"versionLabel":"v1","artifactFingerprint":"%s",
                 "featureSchemaFingerprint":"%s","trainingDatasetFingerprint":"%s",
                 "trainingConfigFingerprint":"%s","codeSha":"%s"}
                """.formatted(A, B, C, D, CODE)))
            .andExpect(status().isCreated()).andReturn()
            .getResponse().getContentAsString();
        return UUID.fromString(mapper.readTree(body).path("versionId").asText());
    }

    private static OffsetDateTime utc(Instant at) {
        return OffsetDateTime.ofInstant(at, ZoneOffset.UTC);
    }
}
