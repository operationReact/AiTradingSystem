package in.aitradingsystem.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import in.aitradingsystem.support.AbstractPostgresIT;
import in.aitradingsystem.domain.Side;
import in.aitradingsystem.paper.PaperFillRepository;
import in.aitradingsystem.paper.PaperFillRepository.FillWrite;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;

class MatchedPaperShadowControllerIT extends AbstractPostgresIT {
    private static final String A = "a".repeat(64);
    private static final String B = "b".repeat(64);
    private static final String C = "c".repeat(64);
    private static final String D = "d".repeat(64);
    private static final String E = "e".repeat(64);
    private static final String CODE = "0123456789abcdef0123456789abcdef01234567";
    private static final String API = "/api/admin/model-governance/paper-shadow";

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired JdbcClient jdbc;
    @Autowired PaperFillRepository fills;

    private final List<UUID> linkedFixtureOrders = new ArrayList<>();

    @AfterEach
    void deleteLinkedPaperOrderFixtures() {
        for (UUID orderId : linkedFixtureOrders) {
            jdbc.sql("DELETE FROM v4_matched_paper_order_link WHERE paper_order_id = :id")
                .param("id", orderId).update();
            jdbc.sql("DELETE FROM paper_fill WHERE order_id = :id")
                .param("id", orderId).update();
            jdbc.sql("DELETE FROM paper_order WHERE id = :id")
                .param("id", orderId).update();
        }
        linkedFixtureOrders.clear();
    }

    @BeforeEach
    void removeGlobalTestQuarantine() throws Exception {
        mvc.perform(put("/api/admin/model-governance/emergency-controls/static-fallback")
                .with(user("matched-admin").roles("ADMIN"))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"enabled\":false,\"reason\":\"matched test isolation\"}"))
            .andExpect(status().isOk());
    }

    @Test
    void adminCanAppendCompleteImmutableMatchedCaseAndReadBackEveryDecision() throws Exception {
        UUID champion = registerModel();
        UUID challenger = registerModel();
        UUID sessionId = UUID.randomUUID();
        String enrollment = enroll(sessionId, champion, challenger);
        JsonNode session = mapper.readTree(enrollment);
        assertEquals("v4-matched-runtime-intake.v1", session.path("contractVersion").asText());
        assertEquals(2, session.path("models").size());
        assertEquals(false, session.path("canAuthorizeCapital").asBoolean());

        Instant decisionAt = Instant.now();
        String event = observationJson(champion, challenger, decisionAt, "ENTER", "WAIT", A, A);
        String first = mvc.perform(post(API + "/sessions/" + sessionId + "/observations")
                .with(user("matched-admin").roles("ADMIN"))
                .contentType(MediaType.APPLICATION_JSON)
                .content(event))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.caseKey").value("case-event-001"))
            .andExpect(jsonPath("$.decisions.length()").value(2))
            .andExpect(jsonPath("$.independentlyVerifiedRuntime").value(false))
            .andExpect(jsonPath("$.paperOrdersSubmitted").value(false))
            .andExpect(jsonPath("$.canAuthorizeCapital").value(false))
            .andReturn().getResponse().getContentAsString();
        String fingerprint = mapper.readTree(first).path("caseFingerprint").asText();

        mvc.perform(get(API + "/sessions/" + sessionId + "/observations/case-event-001")
                .with(user("matched-admin").roles("ADMIN")))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.caseFingerprint").value(fingerprint));
        mvc.perform(get(API + "/sessions/" + sessionId + "/observations?limit=1")
                .with(user("matched-admin").roles("ADMIN")))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$[0].caseFingerprint").value(fingerprint));

        // Exact replay does not write a second case/decision/audit event.
        mvc.perform(post(API + "/sessions/" + sessionId + "/observations")
                .with(user("matched-admin").roles("ADMIN"))
                .contentType(MediaType.APPLICATION_JSON)
                .content(event))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.caseFingerprint").value(fingerprint));
        assertEquals(1, countCases(sessionId));
        assertEquals(2, countDecisions(sessionId));
        assertEquals(0, jdbc.sql("SELECT count(*) FROM paper_order WHERE correlation_id = :id")
            .param("id", sessionId.toString()).query(Integer.class).single());
        assertEquals(1, jdbc.sql("""
                SELECT count(*) FROM audit_event
                 WHERE event_type = 'V4_PAPER_SHADOW_MATCHED_CASE_RECORDED'
                   AND actor = 'matched-admin'
                   AND details ->> 'sessionId' = :id
                """)
            .param("id", sessionId.toString()).query(Integer.class).single());
    }

    @Test
    void conflictingReplayCannotOverwriteOriginalDecisionsOrSession() throws Exception {
        UUID champion = registerModel();
        UUID challenger = registerModel();
        UUID sessionId = UUID.randomUUID();
        enroll(sessionId, champion, challenger);
        Instant time = Instant.now();
        mvc.perform(post(API + "/sessions/" + sessionId + "/observations")
                .with(user("admin").roles("ADMIN"))
                .contentType(MediaType.APPLICATION_JSON)
                .content(observationJson(champion, challenger, time, "ENTER", "WAIT", A, A)))
            .andExpect(status().isCreated());

        mvc.perform(post(API + "/sessions/" + sessionId + "/observations")
                .with(user("admin").roles("ADMIN"))
                .contentType(MediaType.APPLICATION_JSON)
                .content(observationJson(champion, challenger, time, "WAIT", "WAIT", A, A)))
            .andExpect(status().isConflict());
        assertEquals(2, countDecisions(sessionId));
        mvc.perform(get(API + "/sessions/" + sessionId + "/observations/case-event-001")
                .with(user("admin").roles("ADMIN")))
            .andExpect(jsonPath("$.decisions[?(@.role=='PAPER_CHAMPION')].action")
                .value(org.hamcrest.Matchers.hasItem("ENTER")));

        mvc.perform(post(API + "/sessions")
                .with(user("admin").roles("ADMIN"))
                .contentType(MediaType.APPLICATION_JSON)
                .content(enrollJson(sessionId, challenger, champion)))
            .andExpect(status().isConflict());
    }

    @Test
    void noMissingOrLateCandidateOrCausallyUnavailableSourceCanBeStored() throws Exception {
        UUID champion = registerModel();
        UUID challenger = registerModel();
        UUID sessionId = UUID.randomUUID();
        enroll(sessionId, champion, challenger);
        Instant now = Instant.now();

        mvc.perform(post(API + "/sessions/" + sessionId + "/observations")
                .with(user("admin").roles("ADMIN"))
                .contentType(MediaType.APPLICATION_JSON)
                .content(observationJson(champion, challenger, now, "ENTER", "WAIT", A, B)))
            .andExpect(status().isBadRequest());
        mvc.perform(post(API + "/sessions/" + sessionId + "/observations")
                .with(user("admin").roles("ADMIN"))
                .contentType(MediaType.APPLICATION_JSON)
                .content(observationJson(champion, challenger, now.plus(1, ChronoUnit.DAYS),
                    "ENTER", "WAIT", A, A)))
            .andExpect(status().isBadRequest());

        JsonNode missing = mapper.readTree(
            observationJson(champion, challenger, now, "ENTER", "WAIT", A, A));
        ((com.fasterxml.jackson.databind.node.ArrayNode) missing.path("decisions")).remove(1);
        mvc.perform(post(API + "/sessions/" + sessionId + "/observations")
                .with(user("admin").roles("ADMIN"))
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(missing)))
            .andExpect(status().isBadRequest());
        assertEquals(0, countCases(sessionId));
    }

    @Test
    void disabledRegisteredModelIsNotEligibleForFurtherMatchedCapture() throws Exception {
        UUID champion = registerModel();
        UUID challenger = registerModel();
        UUID sessionId = UUID.randomUUID();
        enroll(sessionId, champion, challenger);
        mvc.perform(put("/api/admin/model-governance/emergency-controls/models/" + challenger)
                .with(user("admin").roles("ADMIN"))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"disabled\":true,\"reason\":\"model drift quarantine\"}"))
            .andExpect(status().isOk());
        mvc.perform(post(API + "/sessions/" + sessionId + "/observations")
                .with(user("admin").roles("ADMIN"))
                .contentType(MediaType.APPLICATION_JSON)
                .content(observationJson(champion, challenger, Instant.now(), "ENTER", "WAIT", A, A)))
            .andExpect(status().isConflict());
        assertEquals(0, countCases(sessionId));
    }

    @Test
    void endpointsAreStrictlyAdminOnlyAndHaveBoundedReadLimit() throws Exception {
        UUID sessionId = UUID.randomUUID();
        for (String role : new String[]{"VIEWER", "TRADER", "OPERATOR"}) {
            mvc.perform(get(API + "/sessions/" + sessionId)
                    .with(user("not-admin").roles(role)))
                .andExpect(status().isForbidden());
            mvc.perform(post(API + "/sessions")
                    .with(user("not-admin").roles(role))
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{}"))
                .andExpect(status().isForbidden());
        }
        mvc.perform(get(API + "/sessions/" + sessionId)
                .with(user("admin").roles("ADMIN")))
            .andExpect(status().isNotFound());

        UUID champion = registerModel();
        UUID challenger = registerModel();
        enroll(sessionId, champion, challenger);
        mvc.perform(get(API + "/sessions/" + sessionId + "/observations?limit=1000")
                .with(user("admin").roles("ADMIN")))
            .andExpect(status().isBadRequest());
    }

    @Test
    void linksExistingAutomatedPaperOrderAndReadsAuthoritativeFillCosts() throws Exception {
        UUID champion = registerModel();
        UUID challenger = registerModel();
        UUID sessionId = UUID.randomUUID();
        enroll(sessionId, champion, challenger);
        Instant decision = Instant.now();
        mvc.perform(post(API + "/sessions/" + sessionId + "/observations")
                .with(user("matched-admin").roles("ADMIN"))
                .contentType(MediaType.APPLICATION_JSON)
                .content(observationJson(champion, challenger, decision, "ENTER", "WAIT", A, A)))
            .andExpect(status().isCreated());
        UUID orderId = seedAutomatedPaperOrder("RELIANCE-EQ", "AUTOMATED", decision.plusSeconds(1));
        fills.appendFill(orderId, 1, new FillWrite(
            "RELIANCE-EQ", Side.BUY, 3, new BigDecimal("100.00"), new BigDecimal("100.05"),
            new BigDecimal("300.15"), new BigDecimal("1.25"), new BigDecimal("0.15"),
            new BigDecimal("-301.40"), new BigDecimal("301.40"), BigDecimal.ZERO,
            "PAPER_EXECUTION_V1", "FULL_LIQUIDITY", decision.plusSeconds(2)));
        String link = paperLink(sessionId);
        String body = "{\"paperOrderId\":\"" + orderId + "\"}";

        mvc.perform(post(link)
                .with(user("matched-admin").roles("ADMIN"))
                .contentType(MediaType.APPLICATION_JSON).content(body))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.paperOrderId").value(orderId.toString()))
            .andExpect(jsonPath("$.championModelVersionId").value(champion.toString()))
            .andExpect(jsonPath("$.paperOrderStatus").value("FILLED"))
            .andExpect(jsonPath("$.paperOrderFilledQuantity").value(3))
            .andExpect(jsonPath("$.recordedFillQuantity").value(3))
            .andExpect(jsonPath("$.recordedFeeAmount").value(1.25))
            .andExpect(jsonPath("$.recordedSlippageAmount").value(0.15))
            .andExpect(jsonPath("$.fillQuantityReconciles").value(true))
            .andExpect(jsonPath("$.independentlyVerifiedRuntime").value(false))
            .andExpect(jsonPath("$.brokerOrderSubmitted").value(false))
            .andExpect(jsonPath("$.canAuthorizeCapital").value(false));

        mvc.perform(get(link).with(user("matched-admin").roles("ADMIN")))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.linkedBy").value("matched-admin"))
            .andExpect(jsonPath("$.recordedFeeAmount").value(1.25));
        mvc.perform(post(link).with(user("matched-admin").roles("ADMIN"))
                .contentType(MediaType.APPLICATION_JSON).content(body))
            .andExpect(status().isCreated());
        assertEquals(1, jdbc.sql("""
                SELECT count(*) FROM v4_matched_paper_order_link
                 WHERE session_id = :id AND case_key = 'case-event-001'
                """).param("id", sessionId).query(Integer.class).single());

        UUID alternative = seedAutomatedPaperOrder("RELIANCE-EQ", "AUTOMATED", decision.plusSeconds(1));
        mvc.perform(post(link).with(user("matched-admin").roles("ADMIN"))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"paperOrderId\":\"" + alternative + "\"}"))
            .andExpect(status().isConflict());

        for (String role : new String[]{"VIEWER", "TRADER", "OPERATOR"}) {
            mvc.perform(get(link).with(user("not-admin").roles(role)))
                .andExpect(status().isForbidden());
            mvc.perform(post(link).with(user("not-admin").roles(role))
                    .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isForbidden());
        }
    }

    @Test
    void refusesUnknownManualEarlyWrongSymbolAndNonEnterPaperOrderLinks() throws Exception {
        UUID champion = registerModel();
        UUID challenger = registerModel();
        UUID sessionId = UUID.randomUUID();
        enroll(sessionId, champion, challenger);
        Instant decision = Instant.now();
        mvc.perform(post(API + "/sessions/" + sessionId + "/observations")
                .with(user("matched-admin").roles("ADMIN"))
                .contentType(MediaType.APPLICATION_JSON)
                .content(observationJson(champion, challenger, decision, "ENTER", "WAIT", A, A)))
            .andExpect(status().isCreated());
        String link = paperLink(sessionId);
        mvc.perform(get(link).with(user("matched-admin").roles("ADMIN")))
            .andExpect(status().isNotFound());
        mvc.perform(post(link).with(user("matched-admin").roles("ADMIN"))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"paperOrderId\":\"" + UUID.randomUUID() + "\"}"))
            .andExpect(status().isNotFound());

        UUID manual = seedAutomatedPaperOrder("RELIANCE-EQ", "MANUAL", decision.plusSeconds(1));
        UUID wrongSymbol = seedAutomatedPaperOrder("INFY-EQ", "AUTOMATED", decision.plusSeconds(1));
        UUID beforeDecision = seedAutomatedPaperOrder("RELIANCE-EQ", "AUTOMATED", decision.minusSeconds(1));
        for (UUID id : new UUID[]{manual, wrongSymbol, beforeDecision}) {
            mvc.perform(post(link).with(user("matched-admin").roles("ADMIN"))
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"paperOrderId\":\"" + id + "\"}"))
                .andExpect(status().isBadRequest());
        }

        UUID waitSession = UUID.randomUUID();
        enroll(waitSession, registerModel(), registerModel());
        var enrollment = mapper.readTree(
            mvc.perform(get(API + "/sessions/" + waitSession)
                    .with(user("matched-admin").roles("ADMIN")))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString());
        UUID waitChampion = null;
        UUID waitChallenger = null;
        for (JsonNode model : enrollment.path("models")) {
            if ("PAPER_CHAMPION".equals(model.path("role").asText())) {
                waitChampion = UUID.fromString(model.path("modelVersionId").asText());
            } else {
                waitChallenger = UUID.fromString(model.path("modelVersionId").asText());
            }
        }
        mvc.perform(post(API + "/sessions/" + waitSession + "/observations")
                .with(user("matched-admin").roles("ADMIN"))
                .contentType(MediaType.APPLICATION_JSON)
                .content(observationJson(waitChampion, waitChallenger,
                    Instant.now(), "WAIT", "WAIT", A, A)))
            .andExpect(status().isCreated());
        UUID order = seedAutomatedPaperOrder("RELIANCE-EQ", "AUTOMATED", Instant.now().plusSeconds(1));
        mvc.perform(post(paperLink(waitSession)).with(user("matched-admin").roles("ADMIN"))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"paperOrderId\":\"" + order + "\"}"))
            .andExpect(status().isBadRequest());
    }

    private static String paperLink(UUID sessionId) {
        return API + "/sessions/" + sessionId
            + "/observations/case-event-001/paper-order-link";
    }

    private UUID seedAutomatedPaperOrder(String symbol, String origin, Instant createdAt) {
        UUID id = UUID.randomUUID();
        linkedFixtureOrders.add(id);
        jdbc.sql("""
                INSERT INTO paper_order (
                    id, idempotency_key, request_hash, symbol, side, quantity,
                    submitted_quantity, filled_quantity, reference_price, stop_loss,
                    target_price, status, execution_origin, created_at, updated_at)
                VALUES (:id, :key, :hash, :symbol, 'BUY', 3, 3, 3, 100, 90,
                    110, 'FILLED', :origin, :now, :now)
                """)
            .param("id", id).param("key", "v4-matched-link-" + id).param("hash", A)
            .param("symbol", symbol).param("origin", origin)
            .param("now", OffsetDateTime.ofInstant(createdAt, ZoneOffset.UTC))
            .update();
        return id;
    }

    private int countCases(UUID id) {
        return jdbc.sql("SELECT count(*) FROM v4_matched_case WHERE session_id = :id")
            .param("id", id).query(Integer.class).single();
    }

    private int countDecisions(UUID id) {
        return jdbc.sql("SELECT count(*) FROM v4_matched_decision WHERE session_id = :id")
            .param("id", id).query(Integer.class).single();
    }

    private String enroll(UUID sessionId, UUID champion, UUID challenger) throws Exception {
        String content = enrollJson(sessionId, champion, challenger);
        return mvc.perform(post(API + "/sessions")
                .with(user("matched-admin").roles("ADMIN"))
                .contentType(MediaType.APPLICATION_JSON)
                .content(content))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.createdBy").value("matched-admin"))
            .andReturn().getResponse().getContentAsString();
    }

    private static String enrollJson(UUID sessionId, UUID champion, UUID challenger) {
        return """
            {
              "sessionId": "%s",
              "targetSpecFingerprint": "%s",
              "models": [
                {"modelVersionId":"%s","role":"PAPER_CHAMPION"},
                {"modelVersionId":"%s","role":"SHADOW_CHALLENGER"}
              ]
            }
            """.formatted(sessionId, C, champion, challenger);
    }

    private static String observationJson(
            UUID champion, UUID challenger, Instant when,
            String championAction, String challengerAction,
            String championInput, String challengerInput) {
        return """
            {
              "caseKey":"case-event-001",
              "symbol":"RELIANCE-EQ",
              "decisionAt":"%s",
              "marketAvailableAt":"%s",
              "newsAvailableAt":"%s",
              "marketSourceFingerprint":"%s",
              "newsSourceFingerprint":"%s",
              "inputFingerprint":"%s",
              "decisions":[
                {"modelVersionId":"%s","predictedAt":"%s",
                 "action":"%s","inputFingerprint":"%s"},
                {"modelVersionId":"%s","predictedAt":"%s",
                 "action":"%s","inputFingerprint":"%s"}
              ]
            }
            """.formatted(when, when.minusSeconds(1), when.minusSeconds(1),
                B, D, A,
                champion, when, championAction, championInput,
                challenger, when, challengerAction, challengerInput);
    }

    private UUID registerModel() throws Exception {
        String key = "v4-matched-" + UUID.randomUUID().toString().substring(0, 8);
        mvc.perform(post("/api/admin/model-governance/models")
                .with(user("matched-admin").roles("ADMIN"))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"modelKey":"%s","displayName":"Matched research model",
                     "family":"CATBOOST","role":"ALPHA","provider":"IN_HOUSE",
                     "licenseStatus":"RESEARCH_ONLY"}
                    """.formatted(key)))
            .andExpect(status().isCreated());
        String payload = mvc.perform(post("/api/admin/model-governance/models/" + key + "/versions")
                .with(user("matched-admin").roles("ADMIN"))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"versionLabel":"v1","artifactFingerprint":"%s",
                     "featureSchemaFingerprint":"%s","trainingDatasetFingerprint":"%s",
                     "trainingConfigFingerprint":"%s","codeSha":"%s"}
                    """.formatted(A, B, C, D, CODE)))
            .andExpect(status().isCreated())
            .andReturn().getResponse().getContentAsString();
        return UUID.fromString(mapper.readTree(payload).path("versionId").asText());
    }
}
