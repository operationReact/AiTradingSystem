package in.aitradingsystem.intelligence;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import in.aitradingsystem.support.AbstractPostgresIT;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.NoSuchElementException;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;

/** Real PostgreSQL V61 foreign keys, immutability, state and replay barriers. */
class ResearchInferenceAttemptLedgerIT extends AbstractPostgresIT {
    private static final String A = "a".repeat(64);
    private static final String B = "b".repeat(64);
    private static final String C = "c".repeat(64);
    private static final String D = "d".repeat(64);
    private static final String E = "e".repeat(64);
    private static final String CODE = "0123456789abcdef0123456789abcdef01234567";
    @Autowired JdbcClient jdbc;
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired ResearchInferenceAttemptLedger ledger;

    private record Fixture(UUID session, UUID model, String caseKey) {}

    @BeforeEach
    void resetEmergency() throws Exception {
        mvc.perform(put("/api/admin/model-governance/emergency-controls/static-fallback")
            .with(user("research-admin").roles("ADMIN"))
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"enabled\":false,\"reason\":\"research test isolation\"}"))
            .andExpect(status().isOk());
    }

    @Test
    void reservationIsDurableBeforeTransportAndNeverReplaysMissingReceipt() throws Exception {
        Fixture f = fixture();
        var r = ledger.reserve(f.session(), f.caseKey(), f.model());
        assertEquals(f.session(), r.sessionId());
        assertEquals(A, r.sourceInputFingerprint());
        assertEquals(D, r.featurePayloadFingerprint());
        assertThrows(NoSuchElementException.class,
            () -> ledger.receipt(r.reservationId()));
        // A lost response is not an invitation to send a second request.
        assertThrows(RuntimeException.class,
            () -> ledger.reserve(f.session(), f.caseKey(), f.model()));
        assertEquals(1, jdbc.sql("""
            SELECT count(*) FROM v4_research_inference_reservation
             WHERE session_id=:id AND case_key=:caseKey
            """).param("id", f.session()).param("caseKey", f.caseKey())
            .query(Integer.class).single());
    }

    @Test
    void successfulResultIsImmutableSingleReceiptWithoutCapitalAuthority() throws Exception {
        Fixture f = fixture();
        var r = ledger.reserve(f.session(), f.caseKey(), f.model());
        var validated = new ResearchArtifactInferenceClient.Result(
            f.model(), "CATBOOST", A, f.caseKey(), "RELIANCE",
            A, B, Instant.now().minusSeconds(1), 0.75, "WAIT", E, false);
        var receipt = ledger.succeed(r.reservationId(), validated);
        assertEquals(ResearchInferenceAttemptLedger.Status.SUCCEEDED, receipt.status());
        assertEquals("VALIDATED_RESEARCH_RESPONSE", receipt.reasonCode());
        assertEquals(E, receipt.responseFingerprint());
        assertEquals(0.75, receipt.probability());
        assertFalse(receipt.canAuthorizeCapital());
        assertThrows(RuntimeException.class,
            () -> ledger.succeed(r.reservationId(), validated));
        assertThrows(RuntimeException.class, () -> jdbc.sql("""
            UPDATE v4_research_inference_receipt SET status='UNKNOWN'
             WHERE reservation_id=:id
            """).param("id", r.reservationId()).update());
        assertThrows(RuntimeException.class, () -> jdbc.sql("""
            DELETE FROM v4_research_inference_reservation WHERE reservation_id=:id
            """).param("id", r.reservationId()).update());
        assertEquals(0, jdbc.sql("""
            SELECT count(*) FROM v4_matched_decision
             WHERE session_id=:id
            """).param("id", f.session()).query(Integer.class).single());
    }

    @Test
    void uncertaintyNeverBecomesSuccessAndForgedModelIsRejected() throws Exception {
        Fixture f = fixture();
        var r = ledger.reserve(f.session(), f.caseKey(), f.model());
        var forged = new ResearchArtifactInferenceClient.Result(
            UUID.randomUUID(), "CATBOOST", A, f.caseKey(), "RELIANCE",
            A, B, Instant.now().minusSeconds(1), 0.7, "ENTER", E, false);
        assertThrows(IllegalArgumentException.class,
            () -> ledger.succeed(r.reservationId(), forged));
        var receipt = ledger.fail(r.reservationId(),
            ResearchInferenceAttemptLedger.Status.UNKNOWN,
            ResearchInferenceAttemptLedger.FailureCode.TRANSPORT_UNKNOWN, null);
        assertEquals(ResearchInferenceAttemptLedger.Status.UNKNOWN, receipt.status());
        assertNull(receipt.responseFingerprint());
        assertNull(receipt.probability());
        assertThrows(RuntimeException.class,
            () -> ledger.fail(r.reservationId(),
                ResearchInferenceAttemptLedger.Status.UNKNOWN,
                ResearchInferenceAttemptLedger.FailureCode.TRANSPORT_UNKNOWN, null));
        assertThrows(RuntimeException.class,
            () -> ledger.reserve(f.session(), f.caseKey(), f.model()));
    }

    @Test
    void pauseBlocksNewReservationsButPreservesFailureReceipt() throws Exception {
        Fixture f = fixture();
        var r = ledger.reserve(f.session(), f.caseKey(), f.model());
        mvc.perform(put("/api/admin/model-governance/paper-shadow/sessions/"
                + f.session() + "/control")
            .with(user("research-admin").roles("ADMIN"))
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"action\":\"PAUSE\",\"reason\":\"stop new model evidence\"}"))
            .andExpect(status().isOk());
        assertThrows(IllegalStateException.class,
            () -> ledger.reserve(f.session(), f.caseKey() + "-next", f.model()));
        var receipt = ledger.fail(r.reservationId(),
            ResearchInferenceAttemptLedger.Status.REJECTED,
            ResearchInferenceAttemptLedger.FailureCode.SESSION_REVOKED, null);
        assertEquals(ResearchInferenceAttemptLedger.Status.REJECTED, receipt.status());
        assertThrows(IllegalStateException.class,
            () -> ledger.succeed(r.reservationId(),
                new ResearchArtifactInferenceClient.Result(
                    f.model(), "CATBOOST", A, f.caseKey(), "RELIANCE",
                    A, B, Instant.now(), 0.3, "WAIT", E, false)));
    }

    private Fixture fixture() throws Exception {
        String key = "v61-research-" + UUID.randomUUID().toString().substring(0, 8);
        mvc.perform(post("/api/admin/model-governance/models")
            .with(user("research-admin").roles("ADMIN"))
            .contentType(MediaType.APPLICATION_JSON)
            .content("""
                {"modelKey":"%s","displayName":"V61 Test Model",
                 "family":"CATBOOST","role":"ALPHA","provider":"IN_HOUSE",
                 "licenseStatus":"RESEARCH_ONLY"}
                """.formatted(key))).andExpect(status().isCreated());
        String version = mvc.perform(post(
                "/api/admin/model-governance/models/" + key + "/versions")
            .with(user("research-admin").roles("ADMIN"))
            .contentType(MediaType.APPLICATION_JSON)
            .content("""
                {"versionLabel":"v1","artifactFingerprint":"%s",
                 "featureSchemaFingerprint":"%s","trainingDatasetFingerprint":"%s",
                 "trainingConfigFingerprint":"%s","codeSha":"%s"}
                """.formatted(A, B, C, D, CODE)))
            .andExpect(status().isCreated())
            .andReturn().getResponse().getContentAsString();
        UUID modelId = UUID.fromString(mapper.readTree(version).path("versionId").asText());
        // The V56 roster requires one champion and at least one challenger.
        String challengerKey = "v61-shadow-" + UUID.randomUUID().toString().substring(0, 8);
        mvc.perform(post("/api/admin/model-governance/models")
            .with(user("research-admin").roles("ADMIN"))
            .contentType(MediaType.APPLICATION_JSON)
            .content("""
                {"modelKey":"%s","displayName":"V61 Shadow",
                 "family":"LIGHTGBM","role":"ALPHA","provider":"IN_HOUSE",
                 "licenseStatus":"RESEARCH_ONLY"}
                """.formatted(challengerKey))).andExpect(status().isCreated());
        String shadow = mvc.perform(post(
                "/api/admin/model-governance/models/" + challengerKey + "/versions")
            .with(user("research-admin").roles("ADMIN"))
            .contentType(MediaType.APPLICATION_JSON)
            .content("""
                {"versionLabel":"v1","artifactFingerprint":"%s",
                 "featureSchemaFingerprint":"%s","trainingDatasetFingerprint":"%s",
                 "trainingConfigFingerprint":"%s","codeSha":"%s"}
                """.formatted(A, B, C, D, CODE)))
            .andExpect(status().isCreated())
            .andReturn().getResponse().getContentAsString();
        UUID challenger = UUID.fromString(mapper.readTree(shadow).path("versionId").asText());
        UUID session = UUID.randomUUID();
        mvc.perform(post("/api/admin/model-governance/paper-shadow/sessions")
            .with(user("research-admin").roles("ADMIN"))
            .contentType(MediaType.APPLICATION_JSON)
            .content("""
                {"sessionId":"%s","targetSpecFingerprint":"%s",
                  "models":[
                   {"modelVersionId":"%s","role":"PAPER_CHAMPION"},
                   {"modelVersionId":"%s","role":"SHADOW_CHALLENGER"}]}
                """.formatted(session, C, modelId, challenger)))
            .andExpect(status().isCreated());
        String caseKey = "case-" + UUID.randomUUID().toString().substring(0, 8);
        Instant captured = Instant.now().minusSeconds(30);
        jdbc.sql("""
            INSERT INTO v4_matched_source_capture (
                session_id, case_key, candle_id, news_id, symbol,
                market_available_at, news_available_at, market_snapshot_fingerprint,
                news_snapshot_fingerprint, input_fingerprint, capture_fingerprint,
                captured_at, captured_by)
            VALUES (:session, :caseKey, 1, :news, 'RELIANCE',
                :marketAt, :newsAt, :marketHash, :newsHash,
                :inputHash, :captureHash, :capturedAt, 'v61-test')
            """).param("session", session).param("caseKey", caseKey)
            .param("news", UUID.randomUUID())
            .param("marketAt", utc(captured.minusSeconds(5)))
            .param("newsAt", utc(captured.minusSeconds(5)))
            .param("marketHash", B).param("newsHash", C)
            .param("inputHash", A).param("captureHash", C)
            .param("capturedAt", utc(captured)).update();
        jdbc.sql("""
            INSERT INTO v4_matched_feature_projection (
                session_id, case_key, source_capture_fingerprint,
                previous_candle_id, news_ai_evidence_id, feature_schema_version,
                feature_as_of, latest_feature_available_at, feature_json,
                feature_payload_fingerprint, projected_at, projected_by)
            VALUES (:session, :caseKey, :captureHash, 2, :newsEvidence,
                'features.market.v1', :asOf, :available,
                '{"market.return_1":0.1}', :featureHash, :projected, 'v61-test')
            """).param("session", session).param("caseKey", caseKey)
            .param("captureHash", C).param("newsEvidence", UUID.randomUUID())
            .param("asOf", utc(captured.plusSeconds(1)))
            .param("available", utc(captured))
            .param("featureHash", D).param("projected", utc(captured.plusSeconds(2)))
            .update();
        return new Fixture(session, modelId, caseKey);
    }

    private static OffsetDateTime utc(Instant at) {
        return OffsetDateTime.ofInstant(at, ZoneOffset.UTC);
    }
}
