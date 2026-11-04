package in.aitradingsystem.api;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import in.aitradingsystem.intelligence.ResearchArtifactInferenceClient;
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

/** V62 real database constraints + original V56 atomic append, never broker orders. */
class ResearchMatchedDecisionFinalizeControllerIT extends AbstractPostgresIT {
    private static final String A = "a".repeat(64);
    private static final String B = "b".repeat(64);
    private static final String C = "c".repeat(64);
    private static final String D = "d".repeat(64);
    private static final String E = "e".repeat(64);
    private static final String F = "f".repeat(64);
    private static final String CODE = "0123456789abcdef0123456789abcdef01234567";
    private static final String API = "/api/admin/model-governance/paper-shadow";

    @Autowired MockMvc mvc;
    @Autowired JdbcClient jdbc;
    @Autowired ObjectMapper mapper;
    @Autowired ResearchInferenceAttemptLedger ledger;

    record Fixture(UUID session, String caseKey, UUID champion, UUID challenger) {
        String url() {
            return API + "/sessions/" + session + "/cases/" + caseKey
                + "/finalize-research-evidence";
        }
    }

    @BeforeEach
    void clearGlobalEmergency() throws Exception {
        mvc.perform(put("/api/admin/model-governance/emergency-controls/static-fallback")
                .with(user("v62-admin").roles("ADMIN"))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"enabled\":false,\"reason\":\"v62 test isolation\"}"))
            .andExpect(status().isOk());
    }

    @Test
    void completeRosterAtomicallyAppendsOriginalMatchedCaseAndImmutableCustodyLink()
            throws Exception {
        Fixture f = fixture();
        score(f, f.champion(), "CATBOOST", "ENTER");
        score(f, f.challenger(), "LIGHTGBM", "WAIT");
        mvc.perform(post(f.url()).with(user("v62-admin").roles("ADMIN")))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.contractVersion")
                .value("v4-research-matched-receipt-finalization.v1"))
            .andExpect(jsonPath("$.modelCount").value(2))
            .andExpect(jsonPath("$.independentlyVerifiedRuntime").value(false))
            .andExpect(jsonPath("$.paperOrderSubmitted").value(false))
            .andExpect(jsonPath("$.modelPromotionAllowed").value(false))
            .andExpect(jsonPath("$.canAuthorizeCapital").value(false));
        assertEquals(1, count("v4_matched_case", f));
        assertEquals(2, count("v4_matched_decision", f));
        assertEquals(1, count("v4_research_matched_case_link", f));
        assertEquals(0, jdbc.sql("""
            SELECT count(*) FROM paper_order WHERE correlation_id=:session
            """).param("session", f.session().toString())
            .query(Integer.class).single());
        // Immutable full-roster replay is read-only; no second V56/V62 write.
        mvc.perform(post(f.url()).with(user("v62-admin").roles("ADMIN")))
            .andExpect(status().isCreated()).andExpect(jsonPath("$.modelCount").value(2));
        assertEquals(1, count("v4_matched_case", f));
        assertEquals(1, count("v4_research_matched_case_link", f));
        assertThrows(RuntimeException.class, () -> jdbc.sql("""
            UPDATE v4_research_matched_case_link SET model_count=3
             WHERE session_id=:session AND case_key=:caseKey
            """).param("session", f.session()).param("caseKey", f.caseKey()).update());
    }

    @Test
    void incompleteOrUnknownRosterNeverCreatesMatchedDecision() throws Exception {
        Fixture incomplete = fixture();
        score(incomplete, incomplete.champion(), "CATBOOST", "ENTER");
        mvc.perform(post(incomplete.url()).with(user("v62-admin").roles("ADMIN")))
            .andExpect(status().isConflict());
        assertEquals(0, count("v4_matched_case", incomplete));
        assertEquals(0, count("v4_research_matched_case_link", incomplete));

        Fixture unknown = fixture();
        score(unknown, unknown.champion(), "CATBOOST", "WAIT");
        var reservation = ledger.reserve(
            unknown.session(), unknown.caseKey(), unknown.challenger());
        ledger.fail(reservation.reservationId(), Status.UNKNOWN,
            FailureCode.TRANSPORT_UNKNOWN, null);
        mvc.perform(post(unknown.url()).with(user("v62-admin").roles("ADMIN")))
            .andExpect(status().isConflict());
        assertEquals(0, count("v4_matched_case", unknown));
    }

    @Test
    void staleChangedFeatureEvidenceOrPausedSessionRejectsCompleteReceipts()
            throws Exception {
        Fixture tampered = fixture();
        score(tampered, tampered.champion(), "CATBOOST", "ENTER");
        score(tampered, tampered.challenger(), "LIGHTGBM", "WAIT");
        jdbc.sql("""
            UPDATE v4_matched_feature_projection
               SET feature_payload_fingerprint=:changed
             WHERE session_id=:session AND case_key=:caseKey
            """).param("changed", F).param("session", tampered.session())
            .param("caseKey", tampered.caseKey()).update();
        mvc.perform(post(tampered.url()).with(user("v62-admin").roles("ADMIN")))
            .andExpect(status().isConflict());
        assertEquals(0, count("v4_matched_case", tampered));

        Fixture paused = fixture();
        score(paused, paused.champion(), "CATBOOST", "ENTER");
        score(paused, paused.challenger(), "LIGHTGBM", "WAIT");
        mvc.perform(put(API + "/sessions/" + paused.session() + "/control")
                .with(user("v62-admin").roles("ADMIN"))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"action\":\"PAUSE\",\"reason\":\"untrusted inference provenance\"}"))
            .andExpect(status().isOk());
        mvc.perform(post(paused.url()).with(user("v62-admin").roles("ADMIN")))
            .andExpect(status().isConflict());
        assertEquals(0, count("v4_matched_case", paused));
    }

    @Test
    void noNonAdminMayFinalizeAnyResearchModelRoster() throws Exception {
        Fixture f = fixture();
        for (String role : new String[]{"VIEWER", "TRADER", "OPERATOR"}) {
            mvc.perform(post(f.url()).with(user("not-admin").roles(role)))
                .andExpect(status().isForbidden());
        }
        assertEquals(0, count("v4_matched_case", f));
    }

    private void score(Fixture f, UUID model, String family, String action) {
        var reservation = ledger.reserve(f.session(), f.caseKey(), model);
        Instant now = Instant.now();
        ledger.succeed(reservation.reservationId(),
            new ResearchArtifactInferenceClient.Result(
                model, family, A, f.caseKey(), "RELIANCE", A,
                B, now, 0.7, action, C, false));
    }

    private Fixture fixture() throws Exception {
        UUID champion = register("CATBOOST");
        UUID challenger = register("LIGHTGBM");
        UUID session = UUID.randomUUID();
        mvc.perform(post(API + "/sessions")
                .with(user("v62-admin").roles("ADMIN"))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"sessionId":"%s","targetSpecFingerprint":"%s",
                     "models":[
                      {"modelVersionId":"%s","role":"PAPER_CHAMPION"},
                      {"modelVersionId":"%s","role":"SHADOW_CHALLENGER"}]}
                    """.formatted(session, C, champion, challenger)))
            .andExpect(status().isCreated());
        String caseKey = "case-v62-" + UUID.randomUUID().toString().substring(0, 8);
        Instant captured = Instant.now();
        jdbc.sql("""
            INSERT INTO v4_matched_source_capture (
                session_id, case_key, candle_id, news_id, symbol,
                market_available_at, news_available_at, market_snapshot_fingerprint,
                news_snapshot_fingerprint, input_fingerprint, capture_fingerprint,
                captured_at, captured_by)
            VALUES (:session, :caseKey, 1, :news, 'RELIANCE',
                :marketAt, :newsAt, :marketHash, :newsHash,
                :inputHash, :captureHash, :capturedAt, 'v62-test')
            """).param("session", session).param("caseKey", caseKey)
            .param("news", UUID.randomUUID())
            .param("marketAt", utc(captured.minusSeconds(5)))
            .param("newsAt", utc(captured.minusSeconds(5)))
            .param("marketHash", B).param("newsHash", C)
            .param("inputHash", A).param("captureHash", D)
            .param("capturedAt", utc(captured)).update();
        Instant featureAsOf = Instant.now();
        Instant projectedAt = Instant.now();
        jdbc.sql("""
            INSERT INTO v4_matched_feature_projection (
                session_id, case_key, source_capture_fingerprint,
                previous_candle_id, news_ai_evidence_id, feature_schema_version,
                feature_as_of, latest_feature_available_at, feature_json,
                feature_payload_fingerprint, projected_at, projected_by)
            VALUES (:session, :caseKey, :captureHash, 2, :newsEvidence,
                'features.market.v1', :asOf, :available,
                CAST(:features AS jsonb), :featureHash, :projected, 'v62-test')
            """).param("session", session).param("caseKey", caseKey)
            .param("captureHash", D).param("newsEvidence", UUID.randomUUID())
            .param("asOf", utc(featureAsOf)).param("available", utc(captured))
            .param("features", """
                {"market.return_1":0.1,"market.range_pct":0.2,
                "market.volume_change_1":0.0,
                "event.AI_NEWS_EVIDENCE.direction_score":0.5,
                "event.AI_NEWS_EVIDENCE.confidence":0.8,
                "event.AI_NEWS_EVIDENCE.materiality":0.9}
                """)
            .param("featureHash", E).param("projected", utc(projectedAt))
            .update();
        return new Fixture(session, caseKey, champion, challenger);
    }

    private UUID register(String family) throws Exception {
        String key = "v62-" + family.toLowerCase() + "-"
            + UUID.randomUUID().toString().substring(0, 8);
        mvc.perform(post("/api/admin/model-governance/models")
                .with(user("v62-admin").roles("ADMIN"))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"modelKey":"%s","displayName":"V62 Research",
                     "family":"%s","role":"ALPHA","provider":"IN_HOUSE",
                     "licenseStatus":"RESEARCH_ONLY"}
                    """.formatted(key, family)))
            .andExpect(status().isCreated());
        String body = mvc.perform(post(
                "/api/admin/model-governance/models/" + key + "/versions")
                .with(user("v62-admin").roles("ADMIN"))
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

    private int count(String table, Fixture f) {
        if (!java.util.Set.of(
                "v4_matched_case", "v4_matched_decision",
                "v4_research_matched_case_link").contains(table)) {
            throw new IllegalArgumentException("Only fixed V4 research tables allowed");
        }
        return jdbc.sql("SELECT count(*) FROM " + table
                + " WHERE session_id=:session AND case_key=:caseKey")
            .param("session", f.session()).param("caseKey", f.caseKey())
            .query(Integer.class).single();
    }

    private static OffsetDateTime utc(Instant instant) {
        return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }
}
