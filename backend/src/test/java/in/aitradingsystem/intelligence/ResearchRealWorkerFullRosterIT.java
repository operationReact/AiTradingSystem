package in.aitradingsystem.intelligence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import in.aitradingsystem.governance.ModelRegistryService;
import in.aitradingsystem.support.AbstractPostgresIT;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.springframework.test.web.servlet.MvcResult;
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
    "app.research.roster-dispatch.enabled=true",
    "app.research.worker-readiness.enabled=true"
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
            () -> System.getenv("ATS_V4_RESEARCH_SMOKE_PROXY_URL"));
        registry.add("app.research.artifact-worker.token",
            () -> System.getenv("ATS_V4_RESEARCH_SMOKE_TOKEN"));
    }

    @Test
    void actualAuthenticatedWorkerReadinessMatchesOriginalJavaEnrollmentOnly()
            throws Exception {
        JsonNode manifest = mapper.readTree(Files.readString(
            Path.of(System.getenv("ATS_V4_RESEARCH_SMOKE_MANIFEST"))));
        assertEquals(2, manifest.path("models").size());
        for (JsonNode member : manifest.path("models")) {
            registerVersion(UUID.fromString(member.path("model_version_id").asText()),
                member.path("family").asText(),
                member.path("artifact_fingerprint").asText());
        }
        SyntheticCase enrolled = createSyntheticCase(CAT, LIGHT);
        String readinessUrl = ENDPOINT + "/sessions/" + enrolled.session()
            + "/worker-readiness";
        mvc.perform(get(readinessUrl).with(user("read-only-viewer").roles("VIEWER")))
            .andExpect(status().isForbidden());
        mvc.perform(get(readinessUrl).with(user(ACTOR).roles("ADMIN")))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.contractVersion")
                .value("v4-research-worker-readiness.v1"))
            .andExpect(jsonPath("$.status").value("MATCHED_RESEARCH_ONLY"))
            .andExpect(jsonPath("$.expectedModels").value(2))
            .andExpect(jsonPath("$.workerAllowlistedModels").value(2))
            .andExpect(jsonPath("$.extraWorkerModels").value(0))
            .andExpect(jsonPath("$.workerRosterMatched").value(true))
            .andExpect(jsonPath("$.localWorkerArtifactsValidated").value(true))
            .andExpect(jsonPath("$.independentlyVerifiedDeployment").value(false))
            .andExpect(jsonPath("$.sourceRightsVerified").value(false))
            .andExpect(jsonPath("$.paperShadowSoakAccepted").value(false))
            .andExpect(jsonPath("$.brokerReconciliationAccepted").value(false))
            .andExpect(jsonPath("$.liveExecutionEnabled").value(false))
            .andExpect(jsonPath("$.canAuthorizeCapital").value(false));

        assertEquals(0, count("v4_research_inference_reservation",
            enrolled.session(), enrolled.caseKey()));
        assertEquals(0, count("v4_matched_case",
            enrolled.session(), enrolled.caseKey()));

        UUID unallowlisted = UUID.randomUUID();
        registerVersion(unallowlisted, "LIGHTGBM", "f".repeat(64));
        SyntheticCase mismatch = createSyntheticCase(CAT, unallowlisted);
        mvc.perform(get(ENDPOINT + "/sessions/" + mismatch.session()
                + "/worker-readiness").with(user(ACTOR).roles("ADMIN")))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("MISMATCH_NO_DISPATCH_AUTHORITY"))
            .andExpect(jsonPath("$.workerRosterMatched").value(false))
            .andExpect(jsonPath("$.canAuthorizeCapital").value(false))
            .andExpect(jsonPath("$.independentlyVerifiedDeployment").value(false));
        assertEquals(0, count("v4_research_inference_reservation",
            mismatch.session(), mismatch.caseKey()));
        assertEquals(0, count("v4_matched_case",
            mismatch.session(), mismatch.caseKey()));
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

        SyntheticCase successful = createSyntheticCase(CAT, LIGHT);
        UUID session = successful.session();
        String caseKey = successful.caseKey();
        String route = successful.route();

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

        // Negative end-to-end scenario with the SAME real Python process:
        // an otherwise valid enrolled LightGBM model has no pinned manifest
        // entry. Python responds with a non-200/503. Java must preserve
        // one UNKNOWN receipt and must not manufacture or finalize a match.
        UUID missingWorkerModel = UUID.randomUUID();
        registerVersion(missingWorkerModel, "LIGHTGBM", "f".repeat(64));
        SyntheticCase incomplete = createSyntheticCase(CAT, missingWorkerModel);
        mvc.perform(post(incomplete.route()).with(user(ACTOR).roles("ADMIN")))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("INCOMPLETE_NO_RETRY"))
            .andExpect(jsonPath("$.expectedModels").value(2))
            .andExpect(jsonPath("$.succeeded").value(1))
            .andExpect(jsonPath("$.unresolved").value(1))
            .andExpect(jsonPath("$.matchedEvidenceFinalized").value(false))
            .andExpect(jsonPath("$.canAuthorizeCapital").value(false))
            .andExpect(jsonPath("$.paperOrdersSubmitted").value(false));
        assertEquals(2, count("v4_research_inference_reservation",
            incomplete.session(), incomplete.caseKey()));
        assertEquals(1, jdbc.sql("""
                SELECT count(*) FROM v4_research_inference_receipt receipt
                JOIN v4_research_inference_reservation reservation
                    ON reservation.reservation_id=receipt.reservation_id
                WHERE reservation.session_id=:session AND reservation.case_key=:caseKey
                  AND reservation.model_version_id=:model AND receipt.status='UNKNOWN'
                  AND receipt.reason_code='TRANSPORT_UNKNOWN'
                  AND receipt.response_fingerprint IS NULL
                """)
            .param("session", incomplete.session()).param("caseKey", incomplete.caseKey())
            .param("model", missingWorkerModel).query(Integer.class).single());
        assertEquals(0, count("v4_matched_case",
            incomplete.session(), incomplete.caseKey()));
        assertEquals(0, count("v4_research_matched_case_link",
            incomplete.session(), incomplete.caseKey()));
        assertEquals(0, count("v4_matched_decision",
            incomplete.session(), incomplete.caseKey()));
        mvc.perform(post(incomplete.route()).with(user(ACTOR).roles("ADMIN")))
            .andExpect(status().isConflict());
        assertEquals(2, count("v4_research_inference_reservation",
            incomplete.session(), incomplete.caseKey()));
        assertEquals(0, jdbc.sql("""
                SELECT count(*) FROM paper_order WHERE correlation_id=:correlation
                """).param("correlation", incomplete.session().toString())
            .query(Integer.class).single());
    }


    @Test
    void adminPauseDuringActualWorkerFlightRejectsLateScoresAndPreventsReplay()
            throws Exception {
        JsonNode manifest = mapper.readTree(Files.readString(
            Path.of(System.getenv("ATS_V4_RESEARCH_SMOKE_MANIFEST"))));
        for (JsonNode model : manifest.path("models")) {
            registerVersion(UUID.fromString(model.path("model_version_id").asText()),
                model.path("family").asText(),
                model.path("artifact_fingerprint").asText());
        }
        SyntheticCase paused = createSyntheticCase(CAT, LIGHT);
        String url = System.getenv("ATS_V4_RESEARCH_SMOKE_PROXY_URL");
        assertTrue(url.startsWith("http://127.0.0.1:"),
            "Synthetic pause gate must be loopback only");
        URI gate = URI.create(url);
        HttpClient http = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(Duration.ofSeconds(3)).build();
        assertEquals(200, gatePost(http, gate, "/ci/arm").statusCode());

        var runner = Executors.newSingleThreadExecutor();
        try {
            // MockMvc POST runs in its own thread, and V64's real Python
            // requests are intentionally held AFTER their V61 commits.
            var future = runner.submit(() -> mvc.perform(
                post(paused.route()).with(user(ACTOR).roles("ADMIN")))
                .andReturn());
            boolean allHeld = false;
            int originalRequestCount = -1;
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(7);
            while (System.nanoTime() < deadline) {
                JsonNode telemetry = gateStatus(http, gate);
                if (telemetry.path("held").asInt() >= 2
                        && count("v4_research_inference_reservation",
                            paused.session(), paused.caseKey()) == 2) {
                    allHeld = true;
                    originalRequestCount = telemetry.path("total").asInt();
                    break;
                }
                Thread.sleep(50);
            }
            assertTrue(allHeld,
                "Both research model requests must be held after durable reservations");

            mvc.perform(put(ENDPOINT + "/sessions/" + paused.session() + "/control")
                    .with(user(ACTOR).roles("ADMIN"))
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""
                        {"action":"PAUSE",
                         "reason":"Operator revokes model evidence during worker flight"}
                        """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("PAUSED"));

            // Only NOW allow actual Python trained-model responses to return.
            assertEquals(200, gatePost(http, gate, "/ci/release").statusCode());
            MvcResult completed = future.get(15, TimeUnit.SECONDS);
            assertEquals(200, completed.getResponse().getStatus());
            JsonNode report = mapper.readTree(
                completed.getResponse().getContentAsString());
            assertEquals("INCOMPLETE_NO_RETRY", report.path("status").asText());
            assertFalse(report.path("matchedEvidenceFinalized").asBoolean());
            assertFalse(report.path("canAuthorizeCapital").asBoolean());
            assertFalse(report.path("paperOrdersSubmitted").asBoolean());

            assertEquals(2, count("v4_research_inference_reservation",
                paused.session(), paused.caseKey()));
            assertEquals(0, jdbc.sql("""
                    SELECT count(*) FROM v4_research_inference_receipt receipt
                     JOIN v4_research_inference_reservation reservation
                       ON reservation.reservation_id=receipt.reservation_id
                    WHERE reservation.session_id=:session AND reservation.case_key=:caseKey
                      AND receipt.status='SUCCEEDED'
                    """)
                .param("session", paused.session())
                .param("caseKey", paused.caseKey())
                .query(Integer.class).single());
            assertEquals(2, jdbc.sql("""
                    SELECT count(*) FROM v4_research_inference_receipt receipt
                     JOIN v4_research_inference_reservation reservation
                       ON reservation.reservation_id=receipt.reservation_id
                    WHERE reservation.session_id=:session AND reservation.case_key=:caseKey
                      AND receipt.status='REJECTED'
                      AND receipt.reason_code='SESSION_REVOKED'
                      AND receipt.can_authorize_capital=FALSE
                    """)
                .param("session", paused.session())
                .param("caseKey", paused.caseKey())
                .query(Integer.class).single());
            assertEquals(0, count("v4_matched_case",
                paused.session(), paused.caseKey()));
            assertEquals(0, count("v4_research_matched_case_link",
                paused.session(), paused.caseKey()));
            assertEquals(0, count("v4_matched_decision",
                paused.session(), paused.caseKey()));
            assertEquals(0, jdbc.sql("""
                    SELECT count(*) FROM paper_order WHERE correlation_id=:correlation
                    """).param("correlation", paused.session().toString())
                .query(Integer.class).single());

            mvc.perform(post(paused.route()).with(user(ACTOR).roles("ADMIN")))
                .andExpect(status().isConflict());
            assertEquals(originalRequestCount,
                gateStatus(http, gate).path("total").asInt(),
                "Replayed paused case must not re-contact real Python worker");
        } finally {
            // Never strand delayed HTTP requests on a failing test.
            gatePost(http, gate, "/ci/release");
            runner.shutdownNow();
        }
    }

    private static HttpResponse<String> gatePost(HttpClient http, URI base, String path)
            throws Exception {
        return http.send(HttpRequest.newBuilder(base.resolve(path))
            .timeout(Duration.ofSeconds(3))
            .POST(HttpRequest.BodyPublishers.noBody())
            .build(), HttpResponse.BodyHandlers.ofString());
    }

    private JsonNode gateStatus(HttpClient http, URI base) throws Exception {
        return mapper.readTree(http.send(HttpRequest.newBuilder(
            base.resolve("/ci/held")).timeout(Duration.ofSeconds(3))
            .GET().build(), HttpResponse.BodyHandlers.ofString()).body());
    }


    /**
     * Bounded synthetic failure soak: three independent original sessions,
     * one real trained champion and three different transport/response
     * failures for the already registered LightGBM challenger.
     *
     * The timeout is genuine Java HttpRequest timeout after proxy delay.
     * It remains UNKNOWN because the worker might have scored a request.
     */
    @Test
    @EnabledIfEnvironmentVariable(named = "ATS_V4_FAULT_SOAK", matches = "true")
    void multipleDistinctWorkerFaultsCannotCreateMatchedEvidenceOrRetry()
            throws Exception {
        JsonNode manifest = mapper.readTree(Files.readString(
            Path.of(System.getenv("ATS_V4_RESEARCH_SMOKE_MANIFEST"))));
        assertEquals(2, manifest.path("models").size());
        for (JsonNode model : manifest.path("models")) {
            registerVersion(UUID.fromString(model.path("model_version_id").asText()),
                model.path("family").asText(),
                model.path("artifact_fingerprint").asText());
        }
        URI gate = URI.create(System.getenv("ATS_V4_RESEARCH_SMOKE_PROXY_URL"));
        assertEquals("127.0.0.1", gate.getHost(),
            "Fault injection must stay isolated to local ephemeral CI");
        HttpClient http = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(Duration.ofSeconds(3)).build();

        String[] faults = {"http-503", "timeout", "malformed"};
        String[] receiptStatuses = {"UNKNOWN", "UNKNOWN", "REJECTED"};
        String[] reasons = {
            "TRANSPORT_UNKNOWN", "TRANSPORT_UNKNOWN", "RESPONSE_REJECTED"
        };
        var outcomes = new ArrayList<Map<String, Object>>();
        try {
            for (int index = 0; index < faults.length; index++) {
                String fault = faults[index];
                String expectedStatus = receiptStatuses[index];
                String expectedReason = reasons[index];
                SyntheticCase testCase = createSyntheticCase(CAT, LIGHT);
                int before = gateStatus(http, gate).path("total").asInt();
                assertEquals(200, gatePost(http, gate,
                    "/ci/fault/" + fault).statusCode());
                long start = System.nanoTime();
                MvcResult response = mvc.perform(post(testCase.route())
                    .with(user(ACTOR).roles("ADMIN"))).andReturn();
                long elapsedMs = TimeUnit.NANOSECONDS.toMillis(
                    System.nanoTime() - start);
                assertEquals(200, response.getResponse().getStatus());
                JsonNode body = mapper.readTree(
                    response.getResponse().getContentAsString());
                assertEquals("INCOMPLETE_NO_RETRY", body.path("status").asText());
                assertEquals(2, body.path("expectedModels").asInt());
                assertEquals(1, body.path("succeeded").asInt());
                assertEquals(1, body.path("unresolved").asInt());
                assertFalse(body.path("matchedEvidenceFinalized").asBoolean());
                assertFalse(body.path("paperOrdersSubmitted").asBoolean());
                assertFalse(body.path("canAuthorizeCapital").asBoolean());

                assertEquals(2, count("v4_research_inference_reservation",
                    testCase.session(), testCase.caseKey()));
                assertEquals(1, jdbc.sql("""
                        SELECT count(*) FROM v4_research_inference_receipt receipt
                        JOIN v4_research_inference_reservation reservation
                          ON reservation.reservation_id=receipt.reservation_id
                        WHERE reservation.session_id=:session AND reservation.case_key=:caseKey
                          AND reservation.model_version_id=:model
                          AND receipt.status='SUCCEEDED'
                          AND receipt.can_authorize_capital=FALSE
                        """)
                    .param("session", testCase.session())
                    .param("caseKey", testCase.caseKey())
                    .param("model", CAT).query(Integer.class).single());
                assertEquals(1, jdbc.sql("""
                        SELECT count(*) FROM v4_research_inference_receipt receipt
                        JOIN v4_research_inference_reservation reservation
                          ON reservation.reservation_id=receipt.reservation_id
                        WHERE reservation.session_id=:session AND reservation.case_key=:caseKey
                          AND reservation.model_version_id=:model
                          AND receipt.status=:status
                          AND receipt.reason_code=:reason
                          AND receipt.response_fingerprint IS NULL
                          AND receipt.can_authorize_capital=FALSE
                        """)
                    .param("session", testCase.session())
                    .param("caseKey", testCase.caseKey())
                    .param("model", LIGHT)
                    .param("status", expectedStatus)
                    .param("reason", expectedReason)
                    .query(Integer.class).single());
                assertEquals(0, count("v4_matched_case",
                    testCase.session(), testCase.caseKey()));
                assertEquals(0, count("v4_matched_decision",
                    testCase.session(), testCase.caseKey()));
                assertEquals(0, count("v4_research_matched_case_link",
                    testCase.session(), testCase.caseKey()));
                assertEquals(0, jdbc.sql("""
                        SELECT count(*) FROM paper_order
                        WHERE correlation_id=:correlation
                        """).param("correlation", testCase.session().toString())
                    .query(Integer.class).single());

                JsonNode telemetry = gateStatus(http, gate);
                assertEquals(before + 2, telemetry.path("total").asInt(),
                    "One real HTTP request per roster member, no hidden retries");
                assertEquals(1, telemetry.path("fault_count").asInt(),
                    "Exactly one LightGBM fault injected on this synthetic case");
                mvc.perform(post(testCase.route()).with(user(ACTOR).roles("ADMIN")))
                    .andExpect(status().isConflict());
                assertEquals(before + 2, gateStatus(http, gate)
                    .path("total").asInt(), "No worker replay after an uncertain case");

                // Only counts, bounded wall time and synthetic reason names;
                // never persist session/case IDs, hashes, scores or tokens.
                outcomes.add(Map.of(
                    "fault_mode", fault,
                    "lightgbm_terminal_status", expectedStatus,
                    "reason_code", expectedReason,
                    "wall_clock_ms", elapsedMs,
                    "committed_reservations", 2,
                    "success_receipts", 1,
                    "matched_cases", 0,
                    "paper_orders", 0,
                    "no_retry_verified", true));
                assertEquals(200,
                    gatePost(http, gate, "/ci/fault/clear").statusCode());
            }

            String outputPath = System.getenv("ATS_V4_FAULT_REPORT_PATH");
            if (outputPath == null || outputPath.isBlank()) {
                throw new IllegalStateException("Private synthetic fault report path missing");
            }
            Files.writeString(Path.of(outputPath),
                mapper.writeValueAsString(Map.of(
                    "contract", "v4-synthetic-worker-fault-soak.v1",
                    "synthetic_only", true,
                    "case_count", outcomes.size(),
                    "real_model_families", 2,
                    "injected_faults", outcomes,
                    "capital_authorized", false,
                    "orders_submitted", 0)));
        } finally {
            // A failure cannot leave the CI worker in fault-injection mode.
            assertEquals(200,
                gatePost(http, gate, "/ci/fault/clear").statusCode());
        }
    }

    @Test
    @EnabledIfEnvironmentVariable(named = "ATS_V4_10_MODEL_SOAK", matches = "true")
    void tenModelOneShotRosterProvesBoundedParallelismAndEmitsSyntheticLatency()
            throws Exception {
        JsonNode manifest = mapper.readTree(Files.readString(
            Path.of(System.getenv("ATS_V4_RESEARCH_SMOKE_MANIFEST"))));
        assertEquals(10, manifest.path("models").size(),
            "The bounded V70 test requires exactly ten synthetic allowlisted model IDs");
        var members = new ArrayList<UUID>();
        for (JsonNode model : manifest.path("models")) {
            UUID id = UUID.fromString(model.path("model_version_id").asText());
            registerVersion(id, model.path("family").asText(),
                model.path("artifact_fingerprint").asText());
            members.add(id);
        }
        assertEquals(10, members.stream().distinct().count());
        assertTrue(members.contains(CAT) && members.contains(LIGHT));
        var challengers = new ArrayList<>(members);
        challengers.remove(CAT);
        SyntheticCase cohort = createSyntheticCase(CAT, challengers);
        String proxyUrl = System.getenv("ATS_V4_RESEARCH_SMOKE_PROXY_URL");
        assertTrue(proxyUrl.startsWith("http://127.0.0.1:"),
            "Load evidence must never call a non-loopback worker");
        URI gate = URI.create(proxyUrl);
        HttpClient http = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(Duration.ofSeconds(3)).build();
        int priorRequests = gateStatus(http, gate).path("total").asInt();
        assertEquals(200, gatePost(http, gate, "/ci/arm").statusCode());

        var runner = Executors.newSingleThreadExecutor();
        long start = System.nanoTime();
        try {
            var future = runner.submit(() -> mvc.perform(
                post(cohort.route()).with(user(ACTOR).roles("ADMIN")))
                .andReturn());
            boolean fourInFlight = false;
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(6);
            while (System.nanoTime() < deadline) {
                JsonNode telemetry = gateStatus(http, gate);
                int held = telemetry.path("held").asInt();
                if (held > 4) {
                    throw new IllegalStateException("V64 per-case worker bound exceeded");
                }
                if (held == 4
                        && count("v4_research_inference_reservation",
                            cohort.session(), cohort.caseKey()) == 4) {
                    fourInFlight = true;
                    break;
                }
                Thread.sleep(50);
            }
            assertTrue(fourInFlight,
                "Exactly four real worker requests must be held after reservations");
            // The remaining six should be waiting for bounded worker capacity,
            // not sent to Python or pre-reserved while all four are held.
            Thread.sleep(200);
            assertEquals(4, gateStatus(http, gate).path("held").asInt());
            assertEquals(4, count("v4_research_inference_reservation",
                cohort.session(), cohort.caseKey()));
            assertEquals(200, gatePost(http, gate, "/ci/release").statusCode());
            MvcResult result = future.get(42, TimeUnit.SECONDS);
            long elapsedMs = TimeUnit.NANOSECONDS.toMillis(
                System.nanoTime() - start);
            assertTrue(elapsedMs < 45_000,
                "Research one-shot roster exceeded existing V64 45-second bound");
            assertEquals(200, result.getResponse().getStatus());
            JsonNode body = mapper.readTree(result.getResponse().getContentAsString());
            assertEquals("FINALIZED_RESEARCH_ONLY", body.path("status").asText());
            assertEquals(10, body.path("expectedModels").asInt());
            assertEquals(10, body.path("succeeded").asInt());
            assertEquals(0, body.path("unresolved").asInt());
            assertTrue(body.path("matchedEvidenceFinalized").asBoolean());
            assertFalse(body.path("canAuthorizeCapital").asBoolean());
            assertFalse(body.path("paperOrdersSubmitted").asBoolean());

            assertEquals(10, count("v4_research_inference_reservation",
                cohort.session(), cohort.caseKey()));
            assertEquals(10, count("v4_matched_decision",
                cohort.session(), cohort.caseKey()));
            assertEquals(1, count("v4_matched_case",
                cohort.session(), cohort.caseKey()));
            assertEquals(1, count("v4_research_matched_case_link",
                cohort.session(), cohort.caseKey()));
            assertEquals(10, jdbc.sql("""
                    SELECT count(*) FROM v4_research_inference_receipt receipt
                     JOIN v4_research_inference_reservation reservation
                       ON reservation.reservation_id = receipt.reservation_id
                    WHERE reservation.session_id=:session AND reservation.case_key=:caseKey
                      AND receipt.status='SUCCEEDED'
                      AND receipt.can_authorize_capital=FALSE
                    """).param("session", cohort.session())
                .param("caseKey", cohort.caseKey())
                .query(Integer.class).single());
            assertEquals(0, jdbc.sql("""
                    SELECT count(*) FROM paper_order
                     WHERE correlation_id=:correlation
                    """).param("correlation", cohort.session().toString())
                .query(Integer.class).single());
            assertEquals(priorRequests + 10,
                gateStatus(http, gate).path("total").asInt(),
                "Exactly one real HTTP call per synthetic model is allowed");
            mvc.perform(post(cohort.route()).with(user(ACTOR).roles("ADMIN")))
                .andExpect(status().isConflict());
            assertEquals(priorRequests + 10,
                gateStatus(http, gate).path("total").asInt(),
                "Complete research roster must never replay its remote calls");

            String artifactPath = System.getenv("ATS_V4_SOAK_REPORT_PATH");
            if (artifactPath == null || artifactPath.isBlank()) {
                throw new IllegalStateException("Synthetic evidence file path missing");
            }
            Files.writeString(Path.of(artifactPath), mapper.writeValueAsString(Map.of(
                "contract", "v4-synthetic-roster-throughput.v1",
                "synthetic_only", true,
                "registered_model_ids", 10,
                "distinct_trained_weight_families", 2,
                "observed_held_concurrency", 4,
                "bound_max_parallel_per_case", 4,
                "wall_clock_ms", elapsedMs,
                "success_receipts", 10,
                "paper_orders", 0,
                "can_authorize_capital", false)));
        } finally {
            gatePost(http, gate, "/ci/release");
            runner.shutdownNow();
        }
    }

    private record SyntheticCase(UUID session, String caseKey, String route) {}

    /**
     * Construct strictly synthetic prospective evidence for an independently
     * enrolled research case, with no exchange/news licensing claims.
     */
    private SyntheticCase createSyntheticCase(UUID champion, UUID challenger)
            throws Exception {
        return createSyntheticCase(champion, List.of(challenger));
    }

    private SyntheticCase createSyntheticCase(UUID champion, List<UUID> challengers)
            throws Exception {
        if (champion == null || challengers == null
                || challengers.isEmpty() || challengers.size() > 9
                || challengers.contains(champion)
                || challengers.stream().distinct().count() != challengers.size()) {
            throw new IllegalArgumentException("Unique two-to-ten member roster required");
        }
        UUID session = UUID.randomUUID();
        String caseKey = "ci-v67-" + UUID.randomUUID().toString().substring(0, 8);
        var models = new ArrayList<Map<String, String>>();
        models.add(Map.of("modelVersionId", champion.toString(),
            "role", "PAPER_CHAMPION"));
        for (UUID challenger : challengers) {
            models.add(Map.of("modelVersionId", challenger.toString(),
                "role", "SHADOW_CHALLENGER"));
        }
        mvc.perform(post(ENDPOINT + "/sessions")
                .with(user(ACTOR).roles("ADMIN"))
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(Map.of(
                    "sessionId", session.toString(),
                    "targetSpecFingerprint", C,
                    "models", models))))
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

        return new SyntheticCase(session, caseKey, route);
    }

    private void registerVersion(UUID modelId, String family, String artifactHash) {
        if (!java.util.Set.of("CATBOOST", "LIGHTGBM").contains(family)
                || artifactHash == null || !artifactHash.matches("^[a-f0-9]{64}$")) {
            throw new IllegalArgumentException("Invalid synthetic trained-model identity");
        }
        // The success/UNKNOWN and PAUSE tests share one PostgreSQL database.
        // A duplicate fixed fixture ID is acceptable only for the same exact
        // model family and immutable artifact fingerprint, never a replacement.
        var registered = jdbc.sql("""
                SELECT d.family, v.artifact_fingerprint
                  FROM model_version v JOIN model_definition d
                    ON d.definition_id = v.definition_id
                 WHERE v.version_id=:id
                """).param("id", modelId)
            .query((rs, row) -> new String[]{
                rs.getString("family"), rs.getString("artifact_fingerprint")})
            .optional();
        if (registered.isPresent()) {
            assertEquals(family, registered.get()[0]);
            assertEquals(artifactHash, registered.get()[1]);
            return;
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
