package in.aitradingsystem.intelligence;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import in.aitradingsystem.governance.MatchedCausalFeatureProjectionService.ProjectionView;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/** Independent Python json.dumps golden hashes protect the Java↔Python wire contract. */
class ResearchArtifactInferenceClientTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final UUID MODEL = UUID.fromString("11111111-1111-4111-8111-111111111111");
    private static final String TOKEN = "0123456789abcdef0123456789abcdef";
    private static final String REQUEST_HASH =
        "abe3ba5b5e0b53f525bd2023b1035c66aff0dee0f3e423d8995037f2a54e63de";
    private static final String RESPONSE_HASH =
        "17463705933cfef11133cdd5ccc81ccbbb9fcdf2cf0fc6923cc32ba89fcf1d08";

    private static ProjectionView frozen() {
        return new ProjectionView(
            "v4-java-causal-feature-projection.v1",
            UUID.fromString("22222222-2222-4222-8222-222222222222"),
            "CASE-001", "RELIANCE", "a".repeat(64), "c".repeat(64),
            11L, UUID.fromString("33333333-3333-4333-8333-333333333333"),
            "features.market.v1", Instant.parse("2026-11-01T10:00:00Z"),
            Instant.parse("2026-11-01T09:59:59Z"),
            Map.of(
                "market.return_1", 0.1,
                "market.range_pct", 0.2,
                "market.volume_change_1", 0.0,
                "event.AI_NEWS_EVIDENCE.direction_score", 0.5,
                "event.AI_NEWS_EVIDENCE.confidence", 0.8,
                "event.AI_NEWS_EVIDENCE.materiality", 0.9),
            "d".repeat(64), Instant.parse("2026-11-01T10:00:00Z"),
            "operator", true, false, false, false);
    }

    private static String workerResponse() {
        // SHA-256 computed independently using Python's canonical_fingerprint,
        // not by reusing Java's production hashing method.
        return """
            {
              "contract_version":"bounded-artifact-research-inference.v1",
              "case_key":"CASE-001",
              "symbol":"RELIANCE",
              "model_version_id":"11111111-1111-4111-8111-111111111111",
              "model_family":"CATBOOST",
              "model_artifact_fingerprint":"bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb",
              "source_input_fingerprint":"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
              "feature_payload_fingerprint":"abe3ba5b5e0b53f525bd2023b1035c66aff0dee0f3e423d8995037f2a54e63de",
              "evaluated_at":"2026-11-01T10:00:01Z",
              "probability":0.75,
              "action":"ENTER",
              "status":"ACTUAL_MODEL_COMPUTATION_RESEARCH_ONLY",
              "independent_input_custody_verified":false,
              "model_promotion_allowed":false,
              "can_authorize_capital":false,
              "response_fingerprint":"17463705933cfef11133cdd5ccc81ccbbb9fcdf2cf0fc6923cc32ba89fcf1d08"
            }
            """;
    }

    @Test
    void callsOnlyAllowlistedResearchRouteAndVerifiesPythonCanonicalFingerprints()
            throws Exception {
        var calls = new AtomicInteger();
        var client = new ResearchArtifactInferenceClient(MAPPER, "http://127.0.0.1:8090",
            TOKEN, true, (endpoint, bytes, token) -> {
                calls.incrementAndGet();
                assertEquals("http://127.0.0.1:8090/v1/research/artifact-inference",
                    endpoint.toString());
                assertEquals(TOKEN, token);
                var request = MAPPER.readTree(bytes);
                assertEquals(MODEL.toString(), request.get("model_version_id").asText());
                assertEquals("CASE-001", request.get("case_key").asText());
                assertEquals(6, request.get("features").size());
                assertFalse(request.has("can_authorize_capital"));
                assertFalse(request.has("future_return"));
                return workerResponse().getBytes(StandardCharsets.UTF_8);
            });
        var result = client.score(MODEL, frozen());
        assertEquals(1, calls.get());
        assertEquals("CATBOOST", result.modelFamily());
        assertEquals("ENTER", result.action());
        assertEquals(0.75, result.probability());
        assertEquals(REQUEST_HASH, result.requestFingerprint());
        assertEquals(RESPONSE_HASH, result.responseFingerprint());
        assertFalse(result.canAuthorizeCapital());
    }

    @Test
    void disabledOrMissingSecretNeverCallsTransport() {
        var calls = new AtomicInteger();
        ResearchArtifactInferenceClient.Transport transport = (url, body, token) -> {
            calls.incrementAndGet(); return new byte[0];
        };
        assertThrows(IntelligenceUnavailableException.class,
            () -> new ResearchArtifactInferenceClient(
                MAPPER, "http://127.0.0.1:8090", TOKEN, false, transport)
                .score(MODEL, frozen()));
        assertThrows(IntelligenceUnavailableException.class,
            () -> new ResearchArtifactInferenceClient(
                MAPPER, "http://127.0.0.1:8090", "short", true, transport)
                .score(MODEL, frozen()));
        assertEquals(0, calls.get());
    }

    @Test
    void tokenIsNeverSentToNonLocalPlaintextOrRedirectableUrl() {
        var calls = new AtomicInteger();
        ResearchArtifactInferenceClient.Transport transport = (url, body, token) -> {
            calls.incrementAndGet(); return new byte[0];
        };
        for (String url : new String[]{
                "http://example.com:8090", "http://127.0.0.1:8090/redirect",
                "https://user:pass@example.com", "https://example.com?a=1",
                "file:///etc/passwd"}) {
            var client = new ResearchArtifactInferenceClient(
                MAPPER, url, TOKEN, true, transport);
            assertThrows(IntelligenceUnavailableException.class,
                () -> client.score(MODEL, frozen()));
        }
        assertEquals(0, calls.get());
    }

    @Test
    void rejectsTamperedResponseCapitalAuthorityAndWrongModel() {
        for (String response : new String[]{
                workerResponse().replace("\"can_authorize_capital\":false",
                    "\"can_authorize_capital\":true"),
                workerResponse().replace("17463705933cfef11133cdd5ccc81ccbbb9fcdf2cf0fc6923cc32ba89fcf1d08",
                    "0".repeat(64)),
                workerResponse().replace("11111111-1111-4111-8111-111111111111",
                    "99999999-9999-4999-8999-999999999999"),
                workerResponse().replace("\"model_promotion_allowed\":false",
                    "\"model_promotion_allowed\":true")}) {
            var client = new ResearchArtifactInferenceClient(
                MAPPER, "https://research.example.com", TOKEN, true,
                (url, body, token) -> response.getBytes(StandardCharsets.UTF_8));
            assertThrows(IntelligenceUnavailableException.class,
                () -> client.score(MODEL, frozen()));
        }
    }

    @Test
    void remoteFailureIsUnknownAndNeverAutomaticallyRetried() {
        var calls = new AtomicInteger();
        var client = new ResearchArtifactInferenceClient(
            MAPPER, "https://research.example.com", TOKEN, true,
            (url, body, token) -> {
                calls.incrementAndGet();
                throw new IOException("could contain a secret; never disclose this");
            });
        var error = assertThrows(IntelligenceUnavailableException.class,
            () -> client.score(MODEL, frozen()));
        assertEquals(1, calls.get());
        assertTrue(error.getMessage().contains("UNKNOWN"));
        assertFalse(error.getMessage().contains("secret"));
    }

    @Test
    void refusesUnboundProjectionBeforeAnyNetworkCall() {
        ProjectionView unbound = new ProjectionView(
            frozen().contractVersion(), frozen().sessionId(), frozen().caseKey(),
            frozen().symbol(), frozen().sourceInputFingerprint(),
            frozen().sourceCaptureFingerprint(), frozen().previousCandleId(),
            frozen().newsAiEvidenceId(), frozen().featureSchemaVersion(),
            frozen().featureAsOf(), frozen().latestFeatureAvailableAt(),
            frozen().features(), frozen().featurePayloadFingerprint(),
            frozen().projectedAt(), frozen().projectedBy(), false, false, false, false);
        var client = new ResearchArtifactInferenceClient(MAPPER,
            "https://research.example.com", TOKEN, true,
            (url, body, token) -> fail("Not permitted to score unbound features"));
        assertThrows(IntelligenceUnavailableException.class,
            () -> client.score(MODEL, unbound));
    }
}
