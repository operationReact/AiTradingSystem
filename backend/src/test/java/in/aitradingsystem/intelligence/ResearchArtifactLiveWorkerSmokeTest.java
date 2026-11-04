package in.aitradingsystem.intelligence;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import in.aitradingsystem.governance.MatchedCausalFeatureProjectionService.ProjectionView;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/**
 * Runner-local REAL Java HttpClient -> Python Uvicorn -> serialized CatBoost /
 * LightGBM weights -> validated response bytes. No mock transport is used.
 * Requires explicit opt-in and ephemeral synthetic manifest, never production.
 */
@EnabledIfEnvironmentVariable(named = "ATS_V4_REAL_HTTP_SMOKE", matches = "true")
class ResearchArtifactLiveWorkerSmokeTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final UUID CAT = UUID.fromString(
        "11111111-1111-4111-8111-111111111111");
    private static final UUID LIGHT = UUID.fromString(
        "22222222-2222-4222-8222-222222222222");
    private static final String SOURCE_HASH = "a".repeat(64);
    private static final String FEATURE_HASH = "b".repeat(64);

    private static String env(String name) {
        var value = System.getenv(name);
        assertNotNull(value, "Missing CI-only local smoke input: " + name);
        assertFalse(value.isBlank());
        return value;
    }

    private static ProjectionView frozen() {
        // Seconds only: Python's Pydantic JSON hash uses the same UTC Z form
        // while avoiding nanosecond -> microsecond truncation mismatches.
        var asOf = Instant.now().minusSeconds(15).truncatedTo(ChronoUnit.SECONDS);
        return new ProjectionView(
            "v4-java-causal-feature-projection.v1",
            UUID.fromString("33333333-3333-4333-8333-333333333333"),
            "SYNTHETIC-CASE-001", "RELIANCE", SOURCE_HASH, "c".repeat(64),
            11L, UUID.fromString("44444444-4444-4444-8444-444444444444"),
            "features.market.v1", asOf, asOf.minusSeconds(2),
            Map.of(
                "market.return_1", 0.01,
                "market.range_pct", 0.016,
                "market.volume_change_1", 0.22,
                "event.AI_NEWS_EVIDENCE.direction_score", 0.70,
                "event.AI_NEWS_EVIDENCE.confidence", 0.80,
                "event.AI_NEWS_EVIDENCE.materiality", 0.75
            ),
            FEATURE_HASH, asOf.plusSeconds(1),
            "ci-synthetic-only", true, false, false, false);
    }

    @Test
    void realJavaHttpRoundTripsBothTrainedModelFamiliesAndDeniesWrongToken()
            throws Exception {
        String baseUrl = env("ATS_V4_RESEARCH_SMOKE_BASE_URL");
        String token = env("ATS_V4_RESEARCH_SMOKE_TOKEN");
        assertTrue(baseUrl.startsWith("http://127.0.0.1:"),
            "Smoke must use a private loopback service only");
        assertTrue(token.length() >= 32);

        JsonNode manifest = MAPPER.readTree(
            Files.readString(Path.of(env("ATS_V4_RESEARCH_SMOKE_MANIFEST"))));
        assertEquals("bounded-artifact-research-inference.v1",
            manifest.path("contract_version").asText());
        assertEquals(2, manifest.path("models").size());

        var client = new ResearchArtifactInferenceClient(
            MAPPER, baseUrl, token, true);
        ProjectionView original = frozen();
        // CI-only direct preflight exposes a bounded HTTP status before the
        // production client intentionally maps every non-200 to UNKNOWN.
        // No secrets, response body or model weights are logged.
        var preflightBody = Map.of(
            "model_version_id", CAT.toString(),
            "case_key", original.caseKey(),
            "symbol", original.symbol(),
            "source_input_fingerprint", SOURCE_HASH,
            "feature_as_of", original.featureAsOf().toString(),
            "latest_feature_available_at",
                original.latestFeatureAvailableAt().toString(),
            "features", original.features());
        var preflight = HttpRequest.newBuilder(URI.create(
                baseUrl + "/v1/research/artifact-inference"))
            .timeout(Duration.ofSeconds(10))
            .header("Content-Type", "application/json")
            .header("X-Research-Worker-Token", token)
            .POST(HttpRequest.BodyPublishers.ofString(
                MAPPER.writeValueAsString(preflightBody)))
            .build();
        var preflightReply = HttpClient.newHttpClient().send(
            preflight, HttpResponse.BodyHandlers.discarding());
        assertEquals(200, preflightReply.statusCode(),
            "Synthetic Python worker rejected valid CatBoost request, HTTP "
                + preflightReply.statusCode());

        for (UUID modelId : new UUID[]{CAT, LIGHT}) {
            var result = client.score(modelId, original);
            var expectedFamily = modelId.equals(CAT) ? "CATBOOST" : "LIGHTGBM";
            assertEquals(modelId, result.modelVersionId());
            assertEquals(expectedFamily, result.modelFamily());
            assertEquals(original.caseKey(), result.caseKey());
            assertEquals(original.symbol(), result.symbol());
            assertEquals(SOURCE_HASH, result.sourceInputFingerprint());
            assertTrue(result.probability() >= 0 && result.probability() <= 1);
            assertTrue(java.util.Set.of("ENTER", "WAIT", "ABSTAIN")
                .contains(result.action()));
            assertTrue(result.evaluatedAt().isAfter(original.featureAsOf()));
            assertEquals(64, result.requestFingerprint().length());
            assertEquals(64, result.responseFingerprint().length());
            assertFalse(result.canAuthorizeCapital());
            boolean artifactInManifest = false;
            for (JsonNode model : manifest.path("models")) {
                if (modelId.toString().equals(model.path("model_version_id").asText())) {
                    artifactInManifest = true;
                    assertEquals(expectedFamily, model.path("family").asText());
                    assertEquals(model.path("artifact_fingerprint").asText(),
                        result.artifactFingerprint());
                }
            }
            assertTrue(artifactInManifest);
        }

        // Test the real API auth boundary (not an in-memory mock). Valid
        // six-feature payload is necessary: FastAPI validates before auth.
        var request = Map.of(
            "model_version_id", CAT.toString(),
            "case_key", original.caseKey(),
            "symbol", original.symbol(),
            "source_input_fingerprint", SOURCE_HASH,
            "feature_as_of", original.featureAsOf().toString(),
            "latest_feature_available_at",
                original.latestFeatureAvailableAt().toString(),
            "features", original.features());
        HttpRequest unauthorized = HttpRequest.newBuilder(URI.create(
            baseUrl + "/v1/research/artifact-inference"))
            .timeout(Duration.ofSeconds(10))
            .header("Content-Type", "application/json")
            .header("X-Research-Worker-Token", "wrong-ci-token")
            .POST(HttpRequest.BodyPublishers.ofString(
                MAPPER.writeValueAsString(request)))
            .build();
        var reply = HttpClient.newHttpClient().send(
            unauthorized, HttpResponse.BodyHandlers.discarding());
        assertEquals(401, reply.statusCode());

        // Even a real HTTP 503 for a missing allowlist model is NOT retried.
        var wrongModel = new ResearchArtifactInferenceClient(
            MAPPER, baseUrl, token, true);
        assertThrows(IntelligenceUnavailableException.class,
            () -> wrongModel.score(UUID.fromString(
                "55555555-5555-4555-8555-555555555555"), original));
        assertThrows(IntelligenceUnavailableException.class,
            () -> new ResearchArtifactInferenceClient(
                MAPPER, baseUrl, token, false).score(CAT, original));
    }
}
