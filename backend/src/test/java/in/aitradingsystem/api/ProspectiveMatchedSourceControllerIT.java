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

    @Test
    void freezesSixCausalNumericFeaturesFromRealPersistedMarketAndNewsAnalysis()
            throws Exception {
        UUID session = enroll(registerModel(), registerModel());
        SourceRows rows = sourceRows(Instant.now().minusSeconds(6));
        seedPreviousCandle(rows.candleId());
        seedAiEvidence(rows.newsId(), false);
        String sourceUrl = ENROLL + "/" + session + "/source-cases";
        mvc.perform(post(sourceUrl)
                .with(user("source-admin").roles("ADMIN"))
                .contentType(MediaType.APPLICATION_JSON)
                .content(request(rows.candleId(), rows.newsId())))
            .andExpect(status().isCreated());
        String endpoint = sourceUrl + "/case-0001/features";
        String original = mvc.perform(post(endpoint)
                .with(user("source-admin").roles("ADMIN")))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.contractVersion")
                .value("v4-java-causal-feature-projection.v1"))
            .andExpect(jsonPath("$.featureSchemaVersion").value("features.market.v1"))
            .andExpect(jsonPath("$.symbol").value("RELIANCE-EQ"))
            .andExpect(jsonPath("$.features.size()").value(6))
            .andExpect(jsonPath("$.features['market.return_1']")
                .value(org.hamcrest.Matchers.closeTo(0.005, 1e-8)))
            .andExpect(jsonPath("$.features['event.AI_NEWS_EVIDENCE.direction_score']")
                .value(org.hamcrest.Matchers.closeTo(0.6, 1e-8)))
            .andExpect(jsonPath("$.features['event.AI_NEWS_EVIDENCE.confidence']")
                .value(org.hamcrest.Matchers.closeTo(0.85, 1e-8)))
            .andExpect(jsonPath("$.javaDatabaseBound").value(true))
            .andExpect(jsonPath("$.sourceRightsVerified").value(false))
            .andExpect(jsonPath("$.independentlyVerifiedModelInference").value(false))
            .andExpect(jsonPath("$.canAuthorizeCapital").value(false))
            .andReturn().getResponse().getContentAsString();
        String fingerprint = mapper.readTree(original)
            .path("featurePayloadFingerprint").asText();
        assertEquals(64, fingerprint.length());
        mvc.perform(post(endpoint)
                .with(user("source-admin").roles("ADMIN")))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.featurePayloadFingerprint").value(fingerprint));
        mvc.perform(get(endpoint)
                .with(user("source-admin").roles("ADMIN")))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.featurePayloadFingerprint").value(fingerprint));
        assertEquals(1, jdbc.sql("""
                SELECT count(*) FROM v4_matched_feature_projection
                 WHERE session_id = :id AND case_key = 'case-0001'
                """).param("id", session).query(Integer.class).single());
        mvc.perform(get(endpoint).with(user("viewer").roles("VIEWER")))
            .andExpect(status().isForbidden());
        mvc.perform(post(endpoint).with(user("trader").roles("TRADER")))
            .andExpect(status().isForbidden());
    }

    @Test
    void incompleteOrPostCaptureNewsEvidenceAndMissingHistoryFailClosed()
            throws Exception {
        UUID session = enroll(registerModel(), registerModel());
        SourceRows rows = sourceRows(Instant.now().minusSeconds(6));
        String source = ENROLL + "/" + session + "/source-cases";
        mvc.perform(post(source)
                .with(user("source-admin").roles("ADMIN"))
                .contentType(MediaType.APPLICATION_JSON)
                .content(request(rows.candleId(), rows.newsId())))
            .andExpect(status().isCreated());
        String endpoint = source + "/case-0001/features";
        mvc.perform(post(endpoint).with(user("source-admin").roles("ADMIN")))
            .andExpect(status().isConflict());
        seedPreviousCandle(rows.candleId());
        mvc.perform(post(endpoint).with(user("source-admin").roles("ADMIN")))
            .andExpect(status().isConflict());
        seedAiEvidence(rows.newsId(), true);
        mvc.perform(post(endpoint).with(user("source-admin").roles("ADMIN")))
            .andExpect(status().isConflict());
        assertEquals(0, jdbc.sql("""
                SELECT count(*) FROM v4_matched_feature_projection
                 WHERE session_id = :id
                """).param("id", session).query(Integer.class).single());
    }

    @Test
    void pauseAndModifiedCapturedMarketPricesCannotRecreateFrozenFeatures()
            throws Exception {
        UUID session = enroll(registerModel(), registerModel());
        SourceRows rows = sourceRows(Instant.now().minusSeconds(6));
        seedPreviousCandle(rows.candleId());
        seedAiEvidence(rows.newsId(), false);
        String source = ENROLL + "/" + session + "/source-cases";
        mvc.perform(post(source)
                .with(user("source-admin").roles("ADMIN"))
                .contentType(MediaType.APPLICATION_JSON)
                .content(request(rows.candleId(), rows.newsId())))
            .andExpect(status().isCreated());
        String endpoint = source + "/case-0001/features";
        mvc.perform(put(ENROLL + "/" + session + "/control")
                .with(user("source-admin").roles("ADMIN"))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"action":"PAUSE","reason":"Feed drift investigation"}
                    """))
            .andExpect(status().isOk());
        mvc.perform(post(endpoint).with(user("source-admin").roles("ADMIN")))
            .andExpect(status().isConflict());
        mvc.perform(put(ENROLL + "/" + session + "/control")
                .with(user("source-admin").roles("ADMIN"))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"action":"RESUME","reason":"Investigation cleared feed checks"}
                    """))
            .andExpect(status().isOk());
        jdbc.sql("""
                UPDATE market_candle SET close_price = 100.750000
                 WHERE candle_id = :id
                """).param("id", rows.candleId()).update();
        mvc.perform(post(endpoint).with(user("source-admin").roles("ADMIN")))
            .andExpect(status().isConflict());
    }


    @Test
    void neverMixesUnrelatedInstrumentTokensIntoEarlierReturnFeature()
            throws Exception {
        UUID session = enroll(registerModel(), registerModel());
        SourceRows rows = sourceRows(Instant.now().minusSeconds(6));
        // Same exchange/symbol text but a different exchange instrument token.
        seedPreviousCandle(rows.candleId(), false);
        seedAiEvidence(rows.newsId(), false);
        String base = ENROLL + "/" + session + "/source-cases";
        mvc.perform(post(base)
                .with(user("source-admin").roles("ADMIN"))
                .contentType(MediaType.APPLICATION_JSON)
                .content(request(rows.candleId(), rows.newsId())))
            .andExpect(status().isCreated());
        mvc.perform(post(base + "/case-0001/features")
                .with(user("source-admin").roles("ADMIN")))
            .andExpect(status().isConflict());
        assertEquals(0, jdbc.sql("""
                SELECT COUNT(*) FROM v4_matched_feature_projection WHERE session_id = :id
                """).param("id", session).query(Integer.class).single());
    }

    @Test
    void forbidsBackfilledFeatureSnapshotAfterModelDecisionsAlreadyExist()
            throws Exception {
        UUID champion = registerModel();
        UUID challenger = registerModel();
        UUID session = enroll(champion, challenger);
        SourceRows rows = sourceRows(Instant.now().minusSeconds(6));
        seedPreviousCandle(rows.candleId());
        seedAiEvidence(rows.newsId(), false);
        String base = ENROLL + "/" + session + "/source-cases";
        String payload = mvc.perform(post(base)
                .with(user("source-admin").roles("ADMIN"))
                .contentType(MediaType.APPLICATION_JSON)
                .content(request(rows.candleId(), rows.newsId())))
            .andExpect(status().isCreated())
            .andReturn().getResponse().getContentAsString();
        JsonNode frozen = mapper.readTree(payload);
        Instant predictionAt = Instant.now().truncatedTo(ChronoUnit.MICROS);
        mvc.perform(post(ENROLL + "/" + session + "/observations")
                .with(user("source-admin").roles("ADMIN"))
                .contentType(MediaType.APPLICATION_JSON)
                .content(observation(frozen, champion, challenger,
                    frozen.path("inputFingerprint").asText(), predictionAt)))
            .andExpect(status().isCreated());
        mvc.perform(post(base + "/case-0001/features")
                .with(user("source-admin").roles("ADMIN")))
            .andExpect(status().isConflict());
        assertEquals(0, jdbc.sql("""
                SELECT COUNT(*) FROM v4_matched_feature_projection WHERE session_id = :id
                """).param("id", session).query(Integer.class).single());
    }

    private void seedPreviousCandle(long currentId) {
        seedPreviousCandle(currentId, true);
    }

    private void seedPreviousCandle(long currentId, boolean sameToken) {
        Instant currentCandleAt = jdbc.sql("""
                SELECT candle_time FROM market_candle WHERE candle_id = :id
                """).param("id", currentId)
            .query((rs, n) -> rs.getObject("candle_time", OffsetDateTime.class).toInstant())
            .single();
        Instant currentFetchedAt = jdbc.sql("""
                SELECT fetched_at FROM market_candle WHERE candle_id = :id
                """).param("id", currentId)
            .query((rs, n) -> rs.getObject("fetched_at", OffsetDateTime.class).toInstant())
            .single();
        jdbc.sql("""
                INSERT INTO market_candle (
                    instrument_id, exchange, trading_symbol, symbol_token,
                    interval, candle_time, open_price, high_price, low_price,
                    close_price, volume, source, fetched_at)
                VALUES (1, 'NSE', 'RELIANCE-EQ', :token, 'ONE_MINUTE',
                    :candleTime, 100.000000, 101.000000, 99.000000,
                    100.000000, 7000, 'TEST_PREVIOUS_CANDLE', :fetchedAt)
                """)
            .param("token", sameToken
                ? jdbc.sql("SELECT symbol_token FROM market_candle WHERE candle_id = :id")
                    .param("id", currentId).query(String.class).single()
                : UUID.randomUUID().toString().substring(0, 8))
            .param("candleTime", OffsetDateTime.ofInstant(
                currentCandleAt.minus(1, ChronoUnit.MINUTES), ZoneOffset.UTC))
            .param("fetchedAt", OffsetDateTime.ofInstant(
                currentFetchedAt.minusSeconds(2), ZoneOffset.UTC))
            .update();
    }

    private void seedAiEvidence(UUID newsId, boolean late) {
        Instant fetchedAt = jdbc.sql("""
                SELECT fetched_at FROM news_item WHERE id = :id
                """).param("id", newsId)
            .query((rs, n) -> rs.getObject("fetched_at", OffsetDateTime.class).toInstant())
            .single();
        Instant generated = fetchedAt.plusSeconds(late ? 900 : 1);
        String unique = UUID.randomUUID().toString().replace("-", "").repeat(2);
        jdbc.sql("""
                INSERT INTO news_ai_evidence (
                    id, news_id, provider, model, source_available_at, generated_at,
                    prompt_hash, request_fingerprint, response_fingerprint,
                    sentiment, direction_score, category, confidence,
                    materiality, horizon, novelty, contradiction, ambiguity,
                    impacted_symbols, evidence, risks, latency_ms)
                VALUES (
                    :id, :newsId, 'SYNTHETIC_TEST', 'FAKE_GEMINI',
                    :sourceAt, :generatedAt, :promptHash, :requestHash, :responseHash,
                    'POSITIVE', 0.600, 'EARNINGS', 0.850, 0.750,
                    'INTRADAY', 0.200, FALSE, FALSE, ARRAY['RELIANCE-EQ']::text[],
                    'Synthetic test evidence', ARRAY[]::text[], 3)
                """)
            .param("id", UUID.randomUUID())
            .param("newsId", newsId)
            .param("sourceAt", OffsetDateTime.ofInstant(fetchedAt, ZoneOffset.UTC))
            .param("generatedAt", OffsetDateTime.ofInstant(generated, ZoneOffset.UTC))
            .param("promptHash", A).param("requestHash", unique)
            .param("responseHash", B)
            .update();
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
