package in.aitradingsystem.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import in.aitradingsystem.support.AbstractPostgresIT;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;

class ProspectiveMatchedSourceControllerIT extends AbstractPostgresIT {
    private static final String A = "a".repeat(64);
    private static final String B = "b".repeat(64);
    private static final String C = "c".repeat(64);
    private static final String D = "d".repeat(64);
    private static final String CODE = "0123456789abcdef0123456789abcdef01234567";
    private static final String ENROLL = "/api/admin/model-governance/paper-shadow/sessions";

    @Autowired MockMvc mvc;
    @Autowired JdbcClient jdbc;
    @Autowired ObjectMapper mapper;

    @BeforeEach
    void ensureStaticFallbackIsDisabled() throws Exception {
        mvc.perform(put("/api/admin/model-governance/emergency-controls/static-fallback")
                .with(user("source-admin").roles("ADMIN"))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"enabled\":false,\"reason\":\"source capture test isolation\"}"))
            .andExpect(status().isOk());
    }

    @Test
    void capturesRealPersistedRowsThenEnforcesFrozenSourceAndPredictionTiming() throws Exception {
        UUID champion = registerModel();
        UUID challenger = registerModel();
        UUID sessionId = enroll(champion, challenger);
        SourceRows source = sourceRows(Instant.now().minusSeconds(5));

        String endpoint = ENROLL + "/" + sessionId + "/source-cases";
        String request = request(source.candleId(), source.newsId());
        String result = mvc.perform(post(endpoint)
                .with(user("source-admin").roles("ADMIN"))
                .contentType(MediaType.APPLICATION_JSON).content(request))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.contractVersion").value("v4-java-db-source-capture.v1"))
            .andExpect(jsonPath("$.symbol").value("RELIANCE-EQ"))
            .andExpect(jsonPath("$.candleId").value(source.candleId()))
            .andExpect(jsonPath("$.javaDatabaseBound").value(true))
            .andExpect(jsonPath("$.providerSignatureVerified").value(false))
            .andExpect(jsonPath("$.sourceRightsVerified").value(false))
            .andExpect(jsonPath("$.inferenceExecuted").value(false))
            .andExpect(jsonPath("$.canAuthorizeCapital").value(false))
            .andReturn().getResponse().getContentAsString();
        JsonNode frozen = mapper.readTree(result);

        mvc.perform(get(endpoint + "/case-0001").with(user("source-admin").roles("ADMIN")))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.captureFingerprint").value(
                frozen.path("captureFingerprint").asText()));

        mvc.perform(post(endpoint)
                .with(user("source-admin").roles("ADMIN"))
                .contentType(MediaType.APPLICATION_JSON).content(request))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.captureFingerprint").value(
                frozen.path("captureFingerprint").asText()));
        assertEquals(1, countSources(sessionId));

        // This is no longer an arbitrary client-supplied fingerprint after DB capture.
        mvc.perform(post(ENROLL + "/" + sessionId + "/observations")
                .with(user("source-admin").roles("ADMIN"))
                .contentType(MediaType.APPLICATION_JSON)
                .content(observation(frozen, champion, challenger, "deadbeef".repeat(8), null)))
            .andExpect(status().isBadRequest());

        // The first genuine matched case references the exact frozen server snapshot.
        Instant predicted = Instant.now().truncatedTo(ChronoUnit.MICROS);
        assertTrue(!predicted.isBefore(Instant.parse(frozen.path("capturedAt").asText())));
        String accepted = observation(frozen, champion, challenger,
            frozen.path("inputFingerprint").asText(), predicted);
        mvc.perform(post(ENROLL + "/" + sessionId + "/observations")
                .with(user("source-admin").roles("ADMIN"))
                .contentType(MediaType.APPLICATION_JSON).content(accepted))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.decisions.length()").value(2))
            .andExpect(jsonPath("$.canAuthorizeCapital").value(false));
        assertEquals(1, jdbc.sql("""
                SELECT count(*) FROM v4_matched_case
                 WHERE session_id = :sessionId AND case_key = 'case-0001'
                """)
            .param("sessionId", sessionId).query(Integer.class).single());

        // A backdated model prediction is not allowed after server source capture.
        mvc.perform(post(ENROLL + "/" + sessionId + "/observations")
                .with(user("source-admin").roles("ADMIN"))
                .contentType(MediaType.APPLICATION_JSON)
                .content(observation(frozen, champion, challenger,
                    frozen.path("inputFingerprint").asText(),
                    Instant.parse(frozen.path("capturedAt").asText()).minusSeconds(1))))
            .andExpect(status().isBadRequest());
    }

    @Test
    void sourceEvidenceRejectsStaleRowsWrongSymbolFutureRowsAndConflictingReplay() throws Exception {
        UUID sessionId = enroll(registerModel(), registerModel());
        String endpoint = ENROLL + "/" + sessionId + "/source-cases";

        SourceRows stale = sourceRows(Instant.now().minus(40, ChronoUnit.MINUTES));
        mvc.perform(post(endpoint)
                .with(user("source-admin").roles("ADMIN"))
                .contentType(MediaType.APPLICATION_JSON).content(request(stale.candleId(), stale.newsId())))
            .andExpect(status().isBadRequest());

        SourceRows source = sourceRows(Instant.now().minusSeconds(5));
        UUID wrongNews = createNews("INFY-EQ", Instant.now().minusSeconds(4));
        mvc.perform(post(endpoint)
                .with(user("source-admin").roles("ADMIN"))
                .contentType(MediaType.APPLICATION_JSON).content(request(source.candleId(), wrongNews)))
            .andExpect(status().isNotFound());

        SourceRows future = sourceRows(Instant.now().plus(30, ChronoUnit.MINUTES));
        mvc.perform(post(endpoint)
                .with(user("source-admin").roles("ADMIN"))
                .contentType(MediaType.APPLICATION_JSON).content(request(future.candleId(), future.newsId())))
            .andExpect(status().isBadRequest());

        mvc.perform(post(endpoint)
                .with(user("source-admin").roles("ADMIN"))
                .contentType(MediaType.APPLICATION_JSON).content(request(source.candleId(), source.newsId())))
            .andExpect(status().isCreated());
        SourceRows changed = sourceRows(Instant.now().minusSeconds(5));
        mvc.perform(post(endpoint)
                .with(user("source-admin").roles("ADMIN"))
                .contentType(MediaType.APPLICATION_JSON).content(request(changed.candleId(), changed.newsId())))
            .andExpect(status().isConflict());
        assertEquals(1, countSources(sessionId));
    }

    @Test
    void pausedMatchedSessionRejectsProspectiveSourceCaptureUntilExplicitResume() throws Exception {
        UUID sessionId = enroll(registerModel(), registerModel());
        SourceRows rows = sourceRows(Instant.now().minusSeconds(5));
        String sourceEndpoint = ENROLL + "/" + sessionId + "/source-cases";
        String control = ENROLL + "/" + sessionId + "/control";

        mvc.perform(put(control)
                .with(user("source-admin").roles("ADMIN"))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"action":"PAUSE","reason":"Observed upstream feed drift"}
                    """))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.state").value("PAUSED"));
        mvc.perform(post(sourceEndpoint)
                .with(user("source-admin").roles("ADMIN"))
                .contentType(MediaType.APPLICATION_JSON)
                .content(request(rows.candleId(), rows.newsId())))
            .andExpect(status().isConflict());
        assertEquals(0, countSources(sessionId));

        mvc.perform(put(control)
                .with(user("source-admin").roles("ADMIN"))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"action":"RESUME","reason":"Feed data recovered and checked"}
                    """))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.state").value("ACTIVE"));
        mvc.perform(post(sourceEndpoint)
                .with(user("source-admin").roles("ADMIN"))
                .contentType(MediaType.APPLICATION_JSON)
                .content(request(rows.candleId(), rows.newsId())))
            .andExpect(status().isCreated());
    }

    @Test
    void sourceIntakeIsAdminOnlyAndCannotReferenceUnknownSession() throws Exception {
        SourceRows rows = sourceRows(Instant.now().minusSeconds(5));
        String endpoint = ENROLL + "/" + UUID.randomUUID() + "/source-cases";
        for (String role : new String[]{"VIEWER", "TRADER", "OPERATOR"}) {
            mvc.perform(post(endpoint)
                    .with(user("not-admin").roles(role))
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(request(rows.candleId(), rows.newsId())))
                .andExpect(status().isForbidden());
        }
        mvc.perform(post(endpoint)
                .with(user("source-admin").roles("ADMIN"))
                .contentType(MediaType.APPLICATION_JSON).content(request(rows.candleId(), rows.newsId())))
            .andExpect(status().isNotFound());
    }

    private int countSources(UUID id) {
        return jdbc.sql("SELECT count(*) FROM v4_matched_source_capture WHERE session_id = :id")
            .param("id", id).query(Integer.class).single();
    }

    private static String request(long candleId, UUID newsId) {
        return """
            {"caseKey":"case-0001","candleId":%s,"newsId":"%s"}
            """.formatted(candleId, newsId);
    }

    private static String observation(
            JsonNode frozen, UUID champion, UUID challenger, String inputHash, Instant when) {
        String decision = when == null ? Instant.now().toString() : when.toString();
        return """
            {
              "caseKey":"case-0001",
              "symbol":"RELIANCE-EQ",
              "decisionAt":"%s",
              "marketAvailableAt":"%s",
              "newsAvailableAt":"%s",
              "marketSourceFingerprint":"%s",
              "newsSourceFingerprint":"%s",
              "inputFingerprint":"%s",
              "decisions":[
                {"modelVersionId":"%s","predictedAt":"%s",
                 "action":"ENTER","inputFingerprint":"%s"},
                {"modelVersionId":"%s","predictedAt":"%s",
                 "action":"WAIT","inputFingerprint":"%s"}
              ]
            }
            """.formatted(decision,
                frozen.path("marketAvailableAt").asText(),
                frozen.path("newsAvailableAt").asText(),
                frozen.path("marketSnapshotFingerprint").asText(),
                frozen.path("newsSnapshotFingerprint").asText(),
                inputHash, champion, decision, inputHash, challenger, decision, inputHash);
    }

    private UUID enroll(UUID champion, UUID challenger) throws Exception {
        UUID id = UUID.randomUUID();
        mvc.perform(post(ENROLL)
                .with(user("source-admin").roles("ADMIN"))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"sessionId":"%s","targetSpecFingerprint":"%s",
                     "models":[
                       {"modelVersionId":"%s","role":"PAPER_CHAMPION"},
                       {"modelVersionId":"%s","role":"SHADOW_CHALLENGER"}
                     ]}
                    """.formatted(id, C, champion, challenger)))
            .andExpect(status().isCreated());
        return id;
    }

    private UUID registerModel() throws Exception {
        String key = "source-model-" + UUID.randomUUID().toString().substring(0, 8);
        mvc.perform(post("/api/admin/model-governance/models")
                .with(user("source-admin").roles("ADMIN"))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"modelKey":"%s","displayName":"Source capture model",
                     "family":"CATBOOST","role":"ALPHA","provider":"IN_HOUSE",
                     "licenseStatus":"RESEARCH_ONLY"}
                    """.formatted(key)))
            .andExpect(status().isCreated());
        String version = mvc.perform(post("/api/admin/model-governance/models/" + key + "/versions")
                .with(user("source-admin").roles("ADMIN"))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"versionLabel":"v1","artifactFingerprint":"%s",
                     "featureSchemaFingerprint":"%s","trainingDatasetFingerprint":"%s",
                     "trainingConfigFingerprint":"%s","codeSha":"%s"}
                    """.formatted(A, B, C, D, CODE)))
            .andExpect(status().isCreated())
            .andReturn().getResponse().getContentAsString();
        return UUID.fromString(mapper.readTree(version).path("versionId").asText());
    }


    @Test
    void createsAnImmutablePointInTimeFeatureSnapshotFromActualJavaCandleAndAiEvidence()
            throws Exception {
        UUID session = enroll(registerModel(), registerModel());
        SourceRows rows = sourceRows(Instant.now().minusSeconds(8));
        seedPreviousCandle(rows.candleId());
        seedAiEvidence(rows.newsId(), Instant.now().minusSeconds(7));
        mvc.perform(post(ENROLL + "/" + session + "/source-cases")
                .with(user("source-admin").roles("ADMIN"))
                .contentType(MediaType.APPLICATION_JSON)
                .content(request(rows.candleId(), rows.newsId())))
            .andExpect(status().isCreated());
        String endpoint = ENROLL + "/" + session + "/source-cases/case-0001/features";
        String body = mvc.perform(post(endpoint)
                .with(user("source-admin").roles("ADMIN")))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.contractVersion")
                .value("v4-java-prospective-research-features.v1"))
            .andExpect(jsonPath("$.javaDatabaseBound").value(true))
            .andExpect(jsonPath("$.sourceRightsVerified").value(false))
            .andExpect(jsonPath("$.modelInferenceExecuted").value(false))
            .andExpect(jsonPath("$.canAuthorizeCapital").value(false))
            .andReturn().getResponse().getContentAsString();
        JsonNode result = mapper.readTree(body);
        JsonNode features = result.path("features");
        assertEquals(6, features.size());
        assertEquals(0.75, features.path("event.AI_NEWS_EVIDENCE.direction_score").asDouble(),
            0.000001);
        assertEquals(0.8, features.path("event.AI_NEWS_EVIDENCE.confidence").asDouble(),
            0.000001);
        assertEquals(0.65, features.path("event.AI_NEWS_EVIDENCE.materiality").asDouble(),
            0.000001);
        assertEquals(1.0, features.path("market.volume_change_1").asDouble(), 0.000001);
        assertEquals(2.0 / 100.5, features.path("market.range_pct").asDouble(), 0.000001);
        assertEquals(100.5 / 99.5 - 1.0, features.path("market.return_1").asDouble(),
            0.000001);
        assertTrue(!features.has("forward_return"));
        assertTrue(!features.has("market_outcome"));
        assertTrue(!Instant.parse(result.path("latestFeatureAvailableAt").asText())
            .isAfter(Instant.parse(result.path("featureAsOf").asText())));

        mvc.perform(get(endpoint).with(user("source-admin").roles("ADMIN")))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.featurePayloadFingerprint")
                .value(result.path("featurePayloadFingerprint").asText()));
        mvc.perform(post(endpoint).with(user("source-admin").roles("ADMIN")))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.featurePayloadFingerprint")
                .value(result.path("featurePayloadFingerprint").asText()));
        assertEquals(1, jdbc.sql("""
                SELECT count(*) FROM v4_matched_feature_capture
                 WHERE session_id = :sessionId AND case_key = 'case-0001'
                """).param("sessionId", session).query(Integer.class).single());
        mvc.perform(get(endpoint).with(user("trader").roles("TRADER")))
            .andExpect(status().isForbidden());

        // Original DB row may be mutable; frozen features must never be re-written.
        jdbc.sql("UPDATE market_candle SET close_price = 100.600000 WHERE candle_id = :id")
            .param("id", rows.candleId()).update();
        mvc.perform(get(endpoint).with(user("source-admin").roles("ADMIN")))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.featurePayloadFingerprint")
                .value(result.path("featurePayloadFingerprint").asText()));
    }

    @Test
    void featurePreparationFailsClosedWithoutEarlierBarOrCausallyAvailableAiEvidence()
            throws Exception {
        UUID session = enroll(registerModel(), registerModel());
        SourceRows rows = sourceRows(Instant.now().minusSeconds(8));
        mvc.perform(post(ENROLL + "/" + session + "/source-cases")
                .with(user("source-admin").roles("ADMIN"))
                .contentType(MediaType.APPLICATION_JSON)
                .content(request(rows.candleId(), rows.newsId())))
            .andExpect(status().isCreated());
        String endpoint = ENROLL + "/" + session + "/source-cases/case-0001/features";
        mvc.perform(post(endpoint).with(user("source-admin").roles("ADMIN")))
            .andExpect(status().isConflict());
        seedPreviousCandle(rows.candleId());
        mvc.perform(post(endpoint).with(user("source-admin").roles("ADMIN")))
            .andExpect(status().isConflict());

        // Post-snapshot AI analysis cannot be backfilled as a predecision feature.
        seedAiEvidence(rows.newsId(), Instant.now().plusSeconds(30));
        mvc.perform(post(endpoint).with(user("source-admin").roles("ADMIN")))
            .andExpect(status().isConflict());
        assertEquals(0, jdbc.sql("""
                SELECT count(*) FROM v4_matched_feature_capture
                 WHERE session_id = :session
                """).param("session", session).query(Integer.class).single());
    }

    @Test
    void pausedSessionCannotStartAFeatureCaptureButHistoricalReadbackRemainsPossible()
            throws Exception {
        UUID session = enroll(registerModel(), registerModel());
        SourceRows rows = sourceRows(Instant.now().minusSeconds(8));
        seedPreviousCandle(rows.candleId());
        seedAiEvidence(rows.newsId(), Instant.now().minusSeconds(7));
        mvc.perform(post(ENROLL + "/" + session + "/source-cases")
                .with(user("source-admin").roles("ADMIN"))
                .contentType(MediaType.APPLICATION_JSON)
                .content(request(rows.candleId(), rows.newsId())))
            .andExpect(status().isCreated());
        String endpoint = ENROLL + "/" + session + "/source-cases/case-0001/features";
        mvc.perform(put(ENROLL + "/" + session + "/control")
                .with(user("source-admin").roles("ADMIN"))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"action\":\"PAUSE\",\"reason\":\"Upstream news inference drift\"}"))
            .andExpect(status().isOk());
        mvc.perform(post(endpoint).with(user("source-admin").roles("ADMIN")))
            .andExpect(status().isConflict());
        mvc.perform(get(endpoint).with(user("source-admin").roles("ADMIN")))
            .andExpect(status().isNotFound());
    }

    private void seedPreviousCandle(long currentCandleId) {
        var latest = jdbc.sql("""
                SELECT symbol_token, candle_time, fetched_at
                  FROM market_candle WHERE candle_id = :id
                """)
            .param("id", currentCandleId)
            .query((rs, row) -> new Instant[]{
                rs.getObject("candle_time", OffsetDateTime.class).toInstant(),
                rs.getObject("fetched_at", OffsetDateTime.class).toInstant(),
            })
            .single();
        String token = jdbc.sql("SELECT symbol_token FROM market_candle WHERE candle_id = :id")
            .param("id", currentCandleId).query(String.class).single();
        jdbc.sql("""
                INSERT INTO market_candle (
                    instrument_id, exchange, trading_symbol, symbol_token,
                    interval, candle_time, open_price, high_price, low_price,
                    close_price, volume, source, fetched_at)
                VALUES (
                    1, 'NSE', 'RELIANCE-EQ', :token, 'ONE_MINUTE', :candleAt,
                    99.000000, 100.000000, 98.500000,
                    99.500000, 4000, 'TEST_JAVA_FEED', :fetchedAt)
                """)
            .param("token", token)
            .param("candleAt", OffsetDateTime.ofInstant(latest[0].minusSeconds(60),
                ZoneOffset.UTC))
            .param("fetchedAt", OffsetDateTime.ofInstant(latest[1].minusSeconds(60),
                ZoneOffset.UTC))
            .update();
    }

    private void seedAiEvidence(UUID newsId, Instant generatedAt) {
        Instant sourceAt = jdbc.sql("SELECT fetched_at FROM news_item WHERE id = :id")
            .param("id", newsId)
            .query((rs, row) -> rs.getObject(1, OffsetDateTime.class).toInstant())
            .single();
        UUID id = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO news_ai_evidence (
                    id, news_id, provider, model, source_available_at, generated_at,
                    prompt_hash, request_fingerprint, response_fingerprint,
                    sentiment, direction_score, category, confidence, materiality,
                    horizon, novelty, contradiction, ambiguity, evidence,
                    latency_ms)
                VALUES (
                    :id, :news, 'TEST_GEMINI', 'fixture-model',
                    :sourceAt, :generatedAt, :promptHash, :requestHash, :responseHash,
                    'POSITIVE', 0.750, 'EARNINGS', 0.800, 0.650,
                    'INTRADAY', 0.200, FALSE, FALSE, 'Synthetic test event', 0)
                """)
            .param("id", id).param("news", newsId)
            .param("sourceAt", OffsetDateTime.ofInstant(sourceAt, ZoneOffset.UTC))
            .param("generatedAt", OffsetDateTime.ofInstant(generatedAt, ZoneOffset.UTC))
            .param("promptHash", A).param("requestHash",
                id.toString().replace("-", "").repeat(2))
            .param("responseHash", B)
            .update();
    }

    private record SourceRows(long candleId, UUID newsId) {}

    private SourceRows sourceRows(Instant fetchedAt) {
        Instant recorded = fetchedAt.truncatedTo(ChronoUnit.MICROS);
        String symbolToken = UUID.randomUUID().toString().substring(0, 8);
        Long candle = jdbc.sql("""
                INSERT INTO market_candle (
                    instrument_id, exchange, trading_symbol, symbol_token,
                    interval, candle_time, open_price, high_price, low_price, close_price,
                    volume, source, fetched_at)
                VALUES (1, 'NSE', 'RELIANCE-EQ', :token, 'ONE_MINUTE', :candleAt,
                    100.000000, 101.000000, 99.000000, 100.500000,
                    8000, 'TEST_JAVA_FEED', :fetchedAt)
                RETURNING candle_id
                """)
            .param("token", symbolToken)
            .param("candleAt", OffsetDateTime.ofInstant(recorded.minusSeconds(60), ZoneOffset.UTC))
            .param("fetchedAt", OffsetDateTime.ofInstant(recorded, ZoneOffset.UTC))
            .query(Long.class).single();
        return new SourceRows(candle, createNews("RELIANCE-EQ", recorded));
    }

    private UUID createNews(String symbol, Instant fetchedAt) {
        UUID newsId = UUID.randomUUID();
        String hash = newsId.toString().replace("-", "").repeat(2);
        jdbc.sql("""
                INSERT INTO news_item (
                    id, source, publisher, source_url, published_at, fetched_at, title,
                    normalized_content, content_hash, linked_symbols)
                VALUES (
                    :id, 'TEST_JAVA_NEWS', 'Test Publisher', 'https://example.test/news',
                    :publishedAt, :fetchedAt, 'Test headline',
                    'Synthetic test content', :contentHash, ARRAY[:symbol]::text[])
                """)
            .param("id", newsId)
            .param("publishedAt", OffsetDateTime.ofInstant(fetchedAt.minusSeconds(120), ZoneOffset.UTC))
            .param("fetchedAt", OffsetDateTime.ofInstant(fetchedAt, ZoneOffset.UTC))
            .param("contentHash", hash)
            .param("symbol", symbol)
            .update();
        return newsId;
    }
}
