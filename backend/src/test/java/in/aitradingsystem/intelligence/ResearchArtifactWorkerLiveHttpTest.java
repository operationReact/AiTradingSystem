package in.aitradingsystem.intelligence;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import in.aitradingsystem.governance.MatchedCausalFeatureProjectionService.ProjectionView;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/**
 * True cross-process HTTP: production Java HttpClient -> running Python
 * uvicorn -> allowlisted trained CatBoost/LightGBM weights -> parsed Java
 * validated response. This uses synthetic input, not a Java DB/custody
 * attestation, PAPER order path, paid-source or performance certification.
 *
 * Executed only by the V66 ephemeral loopback CI job, never in normal
 * unit/integration tests or deployments.
 */
@EnabledIfEnvironmentVariable(named = "V4_WORKER_HTTP_SMOKE", matches = "true")
class ResearchArtifactWorkerLiveHttpTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String CASE_KEY = "V66-SYNTHETIC-CASE";
    private static final String SYMBOL = "RELIANCE";
    private static final String SOURCE_HASH = "a".repeat(64);
    private static final String FROZEN_FEATURE_HASH = "b".repeat(64);
    private static final UUID SESSION = UUID.fromString(
        "22222222-2222-4222-8222-222222222222");

    private static ProjectionView frozen() {
        Instant asOf = Instant.now().minusSeconds(90);
        return new ProjectionView(
            "v4-java-causal-feature-projection.v1",
            SESSION, CASE_KEY, SYMBOL, SOURCE_HASH, "c".repeat(64),
            11L, UUID.fromString("44444444-4444-4444-8444-444444444444"),
            "features.market.v1", asOf, asOf.minusSeconds(5),
            Map.of(
                "market.return_1", 0.012,
                "market.range_pct", 0.025,
                "market.volume_change_1", 0.15,
                "event.AI_NEWS_EVIDENCE.direction_score", 0.7,
                "event.AI_NEWS_EVIDENCE.confidence", 0.8,
                "event.AI_NEWS_EVIDENCE.materiality", 0.75),
            FROZEN_FEATURE_HASH, asOf, "v66-test",
            true, false, false, false);
    }

    private static String workerUrl() {
        return required("V4_WORKER_HTTP_URL");
    }

    private static String token() {
        String value = required("V4_WORKER_HTTP_TOKEN");
        assertTrue(value.length() >= 32);
        return value;
    }

    private static String required(String name) {
        String value = System.getenv(name);
        assertNotNull(value, "Required V66 ephemeral smoke environment: " + name);
        assertFalse(value.isBlank());
        return value;
    }

    @Test
    void genuineNetworkScoresBothSerializedModelFamiliesWithoutCapitalAuthority()
            throws Exception {
        String url = workerUrl();
        String secret = token();
        JsonNode manifest = MAPPER.readTree(
            Files.readString(Path.of(required("V4_WORKER_HTTP_MANIFEST"))));
        assertEquals(2, manifest.path("models").size());
        Set<String> expectedFamilies = Set.of("CATBOOST", "LIGHTGBM");
        var observedFamilies = new java.util.HashSet<String>();
        ResearchArtifactInferenceClient client = new ResearchArtifactInferenceClient(
            MAPPER, url, secret, true); // Uses REAL production Java HTTP transport.
        ProjectionView snapshot = frozen();
        for (JsonNode member : manifest.path("models")) {
            UUID model = UUID.fromString(member.path("model_version_id").asText());
            String expectedFamily = member.path("family").asText();
            String expectedArtifact = member.path("artifact_fingerprint").asText();
            assertTrue(expectedFamilies.contains(expectedFamily));
            ResearchArtifactInferenceClient.Result result = client.score(model, snapshot);
            observedFamilies.add(result.modelFamily());
            assertEquals(model, result.modelVersionId());
            assertEquals(expectedFamily, result.modelFamily());
            assertEquals(expectedArtifact, result.artifactFingerprint());
            assertEquals(CASE_KEY, result.caseKey());
            assertEquals(SYMBOL, result.symbol());
            assertEquals(SOURCE_HASH, result.sourceInputFingerprint());
            assertTrue(result.requestFingerprint().matches("[a-f0-9]{64}"));
            assertTrue(result.responseFingerprint().matches("[a-f0-9]{64}"));
            assertTrue(result.evaluatedAt().isAfter(snapshot.featureAsOf()));
            assertTrue(Double.isFinite(result.probability()));
            assertTrue(result.probability() >= 0 && result.probability() <= 1);
            assertTrue(Set.of("ENTER", "WAIT", "ABSTAIN").contains(result.action()));
            assertFalse(result.canAuthorizeCapital());
        }
        assertEquals(expectedFamilies, observedFamilies);
    }

    @Test
    void unauthorizedAndUnregisteredModelsFailClosedOverRealNetwork() {
        String url = workerUrl();
        String secret = token();
        ProjectionView snapshot = frozen();
        UUID model = UUID.fromString("11111111-1111-4111-8111-111111111111");
        var denied = new ResearchArtifactInferenceClient(
            MAPPER, url, "f".repeat(64), true);
        var authError = assertThrows(IntelligenceUnavailableException.class,
            () -> denied.score(model, snapshot));
        assertFalse(authError.getMessage().contains(secret));
        assertFalse(authError.getMessage().contains("f".repeat(64)));

        var enabled = new ResearchArtifactInferenceClient(
            MAPPER, url, secret, true);
        var unknownModel = assertThrows(IntelligenceUnavailableException.class,
            () -> enabled.score(
                UUID.fromString("99999999-9999-4999-8999-999999999999"), snapshot));
        assertTrue(unknownModel.getMessage().contains("UNKNOWN"));
        assertFalse(unknownModel.getMessage().contains(secret));
    }

    @Test
    void disabledClientCannotUseEvenAvailableWorker() {
        var disabled = new ResearchArtifactInferenceClient(
            MAPPER, workerUrl(), token(), false);
        assertThrows(IntelligenceUnavailableException.class,
            () -> disabled.score(
                UUID.fromString("11111111-1111-4111-8111-111111111111"), frozen()));
    }
}
