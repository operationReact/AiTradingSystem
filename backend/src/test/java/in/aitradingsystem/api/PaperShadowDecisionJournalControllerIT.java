package in.aitradingsystem.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import in.aitradingsystem.governance.ModelRegistryService;
import in.aitradingsystem.support.AbstractPostgresIT;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;

class PaperShadowDecisionJournalControllerIT extends AbstractPostgresIT {
    private static final String A = "a".repeat(64);
    private static final String B = "b".repeat(64);
    private static final String C = "c".repeat(64);
    private static final String D = "d".repeat(64);
    private static final String E = "e".repeat(64);

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired JdbcClient jdbc;

    private final List<UUID> definitions = new ArrayList<>();
    private final List<UUID> versions = new ArrayList<>();
    private final List<UUID> sessions = new ArrayList<>();
    private final List<UUID> paperOrders = new ArrayList<>();

    /** Do not let synthetic PAPER fills or immutable FKs contaminate shared PostgreSQL ITs. */
    @AfterEach
    void removeOwnedFixtures() {
        for (UUID sessionId : sessions) {
            jdbc.sql("DELETE FROM v4_paper_shadow_order_link WHERE case_id IN "
                    + "(SELECT case_id FROM v4_paper_shadow_case WHERE session_id = :id)")
                .param("id", sessionId).update();
            jdbc.sql("DELETE FROM v4_paper_shadow_decision WHERE session_id = :id")
                .param("id", sessionId).update();
            jdbc.sql("DELETE FROM v4_paper_shadow_case WHERE session_id = :id")
                .param("id", sessionId).update();
            jdbc.sql("DELETE FROM v4_paper_shadow_model WHERE session_id = :id")
                .param("id", sessionId).update();
            jdbc.sql("DELETE FROM v4_paper_shadow_session WHERE session_id = :id")
                .param("id", sessionId).update();
        }
        for (UUID orderId : paperOrders) {
            jdbc.sql("DELETE FROM paper_fill WHERE order_id = :id").param("id", orderId).update();
            jdbc.sql("DELETE FROM paper_order WHERE id = :id").param("id", orderId).update();
        }
        for (UUID versionId : versions) {
            jdbc.sql("DELETE FROM model_version WHERE version_id = :id")
                .param("id", versionId).update();
        }
        for (UUID definitionId : definitions) {
            jdbc.sql("DELETE FROM model_definition WHERE definition_id = :id")
                .param("id", definitionId).update();
        }
    }

    @Test
    void adminRecordsImmutableMatchedCohortAndReadsEvidenceWithoutTrading() throws Exception {
        UUID champion = modelVersion();
        UUID challenger = modelVersion();
        UUID sessionId = session(champion, challenger);
        String caseKey = "case-" + UUID.randomUUID().toString().substring(0, 8);
        Instant now = Instant.now();
        String captured = caseJson(champion, challenger, caseKey, now, "ENTER", "WAIT", now.minusSeconds(60));
        mvc.perform(post(path(sessionId, "/cases"))
                .with(user("journal-admin").roles("ADMIN"))
                .contentType(MediaType.APPLICATION_JSON).content(captured))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.contractVersion").value("v4-paper-shadow-journal.v1"))
            .andExpect(jsonPath("$.decisions.length()").value(2))
            .andExpect(jsonPath("$.paperFillCount").value(0))
            .andExpect(jsonPath("$.sourceCustodyVerified").value(false))
            .andExpect(jsonPath("$.liveOrdersAllowed").value(false))
            .andExpect(jsonPath("$.canAuthorizeCapital").value(false));

        mvc.perform(post(path(sessionId, "/cases"))
                .with(user("journal-admin").roles("ADMIN"))
                .contentType(MediaType.APPLICATION_JSON).content(captured))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.decisions.length()").value(2));
        Integer recorded = jdbc.sql("""
                SELECT count(*) FROM v4_paper_shadow_case
                 WHERE session_id = :sessionId AND case_key = :caseKey
                """)
            .param("sessionId", sessionId).param("caseKey", caseKey)
            .query(Integer.class).single();
        assertEquals(1, recorded);
        mvc.perform(get(path(sessionId, "/cases"))
                .with(user("journal-admin").roles("ADMIN")))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$[0].caseKey").value(caseKey));
        Integer auditCount = jdbc.sql("""
                SELECT count(*) FROM audit_event
                 WHERE event_type = 'V4_PAPER_SHADOW_CASE_RECORDED'
                   AND actor = 'journal-admin'
                   AND details ->> 'sessionId' = :sessionId
                """)
            .param("sessionId", sessionId.toString())
            .query(Integer.class).single();
        assertEquals(1, auditCount);

        String conflict = caseJson(champion, challenger, caseKey, now, "WAIT", "ENTER", now.minusSeconds(60));
        mvc.perform(post(path(sessionId, "/cases"))
                .with(user("journal-admin").roles("ADMIN"))
                .contentType(MediaType.APPLICATION_JSON).content(conflict))
            .andExpect(status().isConflict());
    }

    @Test
    void paperChampionLinkReferencesOnlyAnExistingAutomatedPaperOrderAndRealFillLedger() throws Exception {
        UUID champion = modelVersion();
        UUID challenger = modelVersion();
        UUID sessionId = session(champion, challenger);
        String key = "fill-" + UUID.randomUUID().toString().substring(0, 8);
        Instant decision = Instant.now();
        mvc.perform(post(path(sessionId, "/cases"))
                .with(user("admin").roles("ADMIN"))
                .contentType(MediaType.APPLICATION_JSON)
                .content(caseJson(champion, challenger, key, decision, "ENTER", "WAIT",
                    decision.minusSeconds(60))))
            .andExpect(status().isCreated());
        UUID orderId = UUID.randomUUID();
        insertPaperOrder(orderId, decision.plusMillis(1), "AUTOMATED", "RELIANCE");
        mvc.perform(post(path(sessionId, "/cases/" + key + "/paper-order-links"))
                .with(user("admin").roles("ADMIN"))
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(Map.of("orderId", orderId))))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.paperOrderId").value(orderId.toString()))
            .andExpect(jsonPath("$.paperFillCount").value(0))
            .andExpect(jsonPath("$.liveOrdersAllowed").value(false));

        insertFill(orderId, decision.plusSeconds(1));
        mvc.perform(get(path(sessionId, "/cases/" + key))
                .with(user("admin").roles("ADMIN")))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.paperFillCount").value(1))
            .andExpect(jsonPath("$.paperFeeAmount").value(1.25))
            .andExpect(jsonPath("$.paperSlippageAmount").value(0.75))
            .andExpect(jsonPath("$.canAuthorizeCapital").value(false));
        mvc.perform(post(path(sessionId, "/cases/" + key + "/paper-order-links"))
                .with(user("admin").roles("ADMIN"))
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(Map.of("orderId", orderId))))
            .andExpect(status().isOk());
        Integer count = jdbc.sql("SELECT count(*) FROM v4_paper_shadow_order_link WHERE order_id = :id")
            .param("id", orderId).query(Integer.class).single();
        assertEquals(1, count);
    }

    @Test
    void rejectLateNewsMissingCandidatesAndShadowOrderLink() throws Exception {
        UUID champion = modelVersion();
        UUID challenger = modelVersion();
        UUID sessionId = session(champion, challenger);
        Instant when = Instant.now();
        String key = "fail-" + UUID.randomUUID().toString().substring(0, 8);
        mvc.perform(post(path(sessionId, "/cases"))
                .with(user("admin").roles("ADMIN"))
                .contentType(MediaType.APPLICATION_JSON)
                .content(caseJson(champion, challenger, key, when, "ENTER", "WAIT",
                    when.plusMillis(1))))
            .andExpect(status().isBadRequest());

        String missing = mapper.writeValueAsString(Map.of(
            "caseKey", key, "symbol", "RELIANCE", "decisionAt", when.toString(),
            "marketAvailableAt", when.minusSeconds(60).toString(),
            "newsAvailableAt", when.minusSeconds(60).toString(),
            "marketFingerprint", A, "newsFingerprint", B, "inputFingerprint", C,
            "decisions", List.of(Map.of("modelVersionId", champion, "predictedAt",
                when.toString(), "action", "ENTER", "modelEvidenceFingerprint", D))));
        mvc.perform(post(path(sessionId, "/cases"))
                .with(user("admin").roles("ADMIN"))
                .contentType(MediaType.APPLICATION_JSON).content(missing))
            .andExpect(status().isBadRequest());

        String abstainKey = "abstain-" + UUID.randomUUID().toString().substring(0, 8);
        Instant second = Instant.now();
        mvc.perform(post(path(sessionId, "/cases"))
                .with(user("admin").roles("ADMIN"))
                .contentType(MediaType.APPLICATION_JSON)
                .content(caseJson(champion, challenger, abstainKey, second, "ABSTAIN", "ENTER",
                    second.minusSeconds(60))))
            .andExpect(status().isCreated());
        mvc.perform(post(path(sessionId, "/cases/" + abstainKey + "/paper-order-links"))
                .with(user("admin").roles("ADMIN"))
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(Map.of("orderId", UUID.randomUUID()))))
            .andExpect(status().isConflict());
    }

    @Test
    void nonAdminRolesCannotWriteOrReadModelDecisions() throws Exception {
        UUID champion = modelVersion();
        UUID challenger = modelVersion();
        UUID sessionId = session(champion, challenger);
        for (String role : List.of("VIEWER", "TRADER", "OPERATOR")) {
            mvc.perform(get(path(sessionId, ""))
                    .with(user(role.toLowerCase()).roles(role)))
                .andExpect(status().isForbidden());
            mvc.perform(post(path(sessionId, "/cases"))
                    .with(user(role.toLowerCase()).roles(role))
                    .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isForbidden());
        }
    }

    @Test
    void unregisteredModelOrInvalidSessionConfigurationIsRejected() throws Exception {
        UUID champion = modelVersion();
        UUID challenger = modelVersion();
        mvc.perform(post("/api/admin/model-governance/paper-shadow/sessions")
                .with(user("admin").roles("ADMIN"))
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(Map.of(
                    "sourceKind", "CI_SYNTHETIC", "cohortFingerprint", A,
                    "targetFingerprint", B,
                    "models", List.of(
                        Map.of("modelVersionId", champion, "role", "PAPER_CHAMPION"),
                        Map.of("modelVersionId", challenger, "role", "PAPER_CHAMPION"))))))
            .andExpect(status().isBadRequest());
        UUID session = session(champion, challenger);
        Instant now = Instant.now();
        String key = "other-" + UUID.randomUUID().toString().substring(0, 8);
        String altered = caseJson(champion, UUID.randomUUID(), key, now,
            "ENTER", "WAIT", now.minusSeconds(60));
        mvc.perform(post(path(session, "/cases"))
                .with(user("admin").roles("ADMIN"))
                .contentType(MediaType.APPLICATION_JSON).content(altered))
            .andExpect(status().isBadRequest());
    }

    private UUID modelVersion() {
        UUID definitionId = UUID.randomUUID();
        UUID versionId = UUID.randomUUID();
        definitions.add(definitionId);
        versions.add(versionId);
        String key = "journal-" + definitionId.toString().substring(0, 12);
        jdbc.sql("""
                INSERT INTO model_definition
                    (definition_id, model_key, display_name, family, model_role, provider,
                     license_status, definition_fingerprint, created_by)
                VALUES (:id, :key, 'Journal Test Model', 'CATBOOST', 'ALPHA', 'TEST_LOCAL',
                        'RESEARCH_ONLY', :fingerprint, 'test')
                """)
            .param("id", definitionId).param("key", key)
            .param("fingerprint", ModelRegistryService.fingerprint("journal-test", key))
            .update();
        jdbc.sql("""
                INSERT INTO model_version
                    (version_id, definition_id, version_label, artifact_fingerprint,
                     feature_schema_fingerprint, training_dataset_fingerprint,
                     training_config_fingerprint, code_sha, version_fingerprint, created_by)
                VALUES (:id, :definitionId, 'v1', :a, :b, :c, :d,
                        '0123456789012345678901234567890123456789', :fingerprint, 'test')
                """)
            .param("id", versionId).param("definitionId", definitionId)
            .param("a", A).param("b", B).param("c", C).param("d", D)
            .param("fingerprint", ModelRegistryService.fingerprint("journal-test", versionId.toString()))
            .update();
        return versionId;
    }

    private UUID session(UUID champion, UUID challenger) throws Exception {
        var request = Map.of(
            "sourceKind", "CI_SYNTHETIC",
            "cohortFingerprint", A, "targetFingerprint", B,
            "models", List.of(
                Map.of("modelVersionId", champion, "role", "PAPER_CHAMPION"),
                Map.of("modelVersionId", challenger, "role", "SHADOW_CHALLENGER")));
        String json = mvc.perform(post("/api/admin/model-governance/paper-shadow/sessions")
                .with(user("admin").roles("ADMIN"))
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(request)))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.models.length()").value(2))
            .andExpect(jsonPath("$.canAuthorizeCapital").value(false))
            .andReturn().getResponse().getContentAsString();
        JsonNode parsed = mapper.readTree(json);
        UUID id = UUID.fromString(parsed.path("sessionId").asText());
        assertNotNull(id);
        sessions.add(id);
        return id;
    }

    private String caseJson(UUID champion, UUID challenger, String key, Instant when,
                            String championAction, String challengerAction,
                            Instant newsAt) throws Exception {
        return mapper.writeValueAsString(Map.of(
            "caseKey", key, "symbol", "RELIANCE", "decisionAt", when.toString(),
            "marketAvailableAt", when.minusSeconds(60).toString(),
            "newsAvailableAt", newsAt.toString(),
            "marketFingerprint", A, "newsFingerprint", B, "inputFingerprint", C,
            "decisions", List.of(
                Map.of("modelVersionId", champion, "predictedAt", when.toString(),
                    "action", championAction, "modelEvidenceFingerprint", D),
                Map.of("modelVersionId", challenger, "predictedAt", when.toString(),
                    "action", challengerAction, "modelEvidenceFingerprint", E))));
    }

    private void insertPaperOrder(UUID id, Instant createdAt, String origin, String symbol) {
        paperOrders.add(id);
        jdbc.sql("""
                INSERT INTO paper_order
                    (id, idempotency_key, request_hash, symbol, side, quantity,
                     reference_price, stop_loss, target_price, status, created_at,
                     updated_at, execution_origin, submitted_quantity)
                VALUES (:id, :key, :hash, :symbol, 'BUY', 1, 100, 90, 110,
                        'FILLED', :createdAt, :createdAt, :origin, 1)
                """)
            .param("id", id).param("key", "journal-test-" + id).param("hash", A)
            .param("symbol", symbol).param("createdAt", OffsetDateTime.ofInstant(createdAt, ZoneOffset.UTC))
            .param("origin", origin).update();
    }

    private void insertFill(UUID orderId, Instant filledAt) {
        jdbc.sql("""
                INSERT INTO paper_fill
                    (id, order_id, symbol, side, quantity, reference_price,
                     fill_price, gross_amount, fee_amount, slippage_amount, net_amount,
                     cost_basis, realized_pnl, filled_at)
                VALUES (:id, :orderId, 'RELIANCE', 'BUY', 1, 100, 100.75,
                        100.75, 1.25, 0.75, 102.75, 100.75, 0, :filledAt)
                """)
            .param("id", UUID.randomUUID()).param("orderId", orderId)
            .param("filledAt", OffsetDateTime.ofInstant(filledAt, ZoneOffset.UTC))
            .update();
    }

    private static String path(UUID sessionId, String suffix) {
        return "/api/admin/model-governance/paper-shadow/sessions/" + sessionId + suffix;
    }
}
