package in.aitradingsystem.intelligence;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import in.aitradingsystem.governance.MatchedCausalFeatureProjectionService.ProjectionView;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Opt-in, internal-only HTTP adapter for PR #400's actual trained-artifact inference.
 * This component deliberately does not publish V56 decisions, submit PAPER orders,
 * enable models, or authorize capital. A future orchestrator must own ADMIN/PAPER,
 * V58 safety, durable per-model receipts and matched-decision atomicity.
 */
@Component
public final class ResearchArtifactInferenceClient {
    public static final String CONTRACT = "bounded-artifact-research-inference.v1";
    private static final String PATH = "/v1/research/artifact-inference";
    private static final String HASH = "^[a-f0-9]{64}$";
    private static final Set<String> RESPONSE_FIELDS = Set.of(
        "contract_version", "case_key", "symbol", "model_version_id",
        "model_family", "model_artifact_fingerprint", "source_input_fingerprint",
        "feature_payload_fingerprint", "evaluated_at", "probability", "action",
        "status", "independent_input_custody_verified", "model_promotion_allowed",
        "can_authorize_capital", "response_fingerprint");

    @FunctionalInterface
    interface Transport {
        byte[] post(URI endpoint, byte[] payload, String token)
            throws IOException, InterruptedException;
    }

    /** No redirects (including credential-bearing 3xx); no retries after UNKNOWN. */
    private static final Transport HTTP_TRANSPORT = (endpoint, payload, token) -> {
        HttpClient client = HttpClient.newBuilder()
            // Python Uvicorn/h11 accepts HTTP/1.1, not Java's default cleartext
            // h2c upgrade. With h2c Upgrade + Content-Length, Uvicorn sees an
            // empty body (FastAPI 422) despite the client publishing bytes.
            // Explicit HTTP/1.1 also works with our HTTPS-only nonloopback
            // policy and preserves the no-redirect/no-retry safety boundary.
            .version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(Duration.ofSeconds(3))
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();
        HttpRequest request = HttpRequest.newBuilder(endpoint)
            .timeout(Duration.ofSeconds(10))
            .header("Content-Type", "application/json")
            .header("Accept", "application/json")
            .header("X-Research-Worker-Token", token)
            .POST(HttpRequest.BodyPublishers.ofByteArray(payload))
            .build();
        HttpResponse<byte[]> response = client.send(
            request, HttpResponse.BodyHandlers.ofByteArray());
        if (response.statusCode() != 200) {
            // The remote body may contain untrusted text or operational secrets.
            throw new IOException("Research worker returned an unsuccessful HTTP status");
        }
        return response.body();
    };

    public record Result(
        UUID modelVersionId, String modelFamily, String artifactFingerprint,
        String caseKey, String symbol, String sourceInputFingerprint,
        String requestFingerprint, Instant evaluatedAt, double probability,
        String action, String responseFingerprint,
        boolean canAuthorizeCapital) {}

    private final ObjectMapper mapper;
    private final String baseUrl;
    private final String token;
    private final String trustedHost;
    private final boolean enabled;
    private final Transport transport;

    @Autowired
    public ResearchArtifactInferenceClient(
            ObjectMapper mapper,
            @Value("${app.research.artifact-worker.base-url:}") String baseUrl,
            @Value("${app.research.artifact-worker.token:}") String token,
            @Value("${app.research.artifact-worker.enabled:false}") boolean enabled,
            @Value("${app.research.artifact-worker.trusted-host:}") String trustedHost) {
        this(mapper, baseUrl, token, enabled, trustedHost, HTTP_TRANSPORT);
    }

    // Historical four-argument test seam used by running-Python smoke tests.
    // Production Spring uses the @Autowired constructor with the independent
    // trusted-host configuration; this overload never receives Spring injection.
    ResearchArtifactInferenceClient(
            ObjectMapper mapper, String baseUrl, String token, boolean enabled) {
        this(mapper, baseUrl, token, enabled,
            ResearchWorkerTlsOrigin.syntheticTestPin(baseUrl), HTTP_TRANSPORT);
    }

    // Package-private seam for deterministic local/synthetic tests only.
    ResearchArtifactInferenceClient(
            ObjectMapper mapper, String baseUrl, String token,
            boolean enabled, Transport transport) {
        this(mapper, baseUrl, token, enabled,
            ResearchWorkerTlsOrigin.syntheticTestPin(baseUrl), transport);
    }

    ResearchArtifactInferenceClient(
            ObjectMapper mapper, String baseUrl, String token,
            boolean enabled, String trustedHost, Transport transport) {
        this.mapper = mapper;
        this.baseUrl = baseUrl;
        this.token = token;
        this.trustedHost = trustedHost;
        this.enabled = enabled;
        this.transport = transport;
    }

    public Result score(UUID modelVersionId, ProjectionView frozen) {
        if (!enabled || token == null || token.length() < 32) {
            throw new IntelligenceUnavailableException("Research artifact inference is disabled");
        }
        URI endpoint = validatedEndpoint(baseUrl);
        ResearchWorkerTlsOrigin.requirePinned(endpoint, trustedHost);
        if (modelVersionId == null || frozen == null || !frozen.javaDatabaseBound()
                || frozen.canAuthorizeCapital() || frozen.independentlyVerifiedModelInference()
                || !"features.market.v1".equals(frozen.featureSchemaVersion())
                || frozen.sourceInputFingerprint() == null
                || !frozen.sourceInputFingerprint().matches(HASH)
                || frozen.featurePayloadFingerprint() == null
                || !frozen.featurePayloadFingerprint().matches(HASH)
                || frozen.caseKey() == null
                || !frozen.caseKey().matches("^[A-Za-z0-9][A-Za-z0-9._-]{2,95}$")
                || frozen.symbol() == null
                || !frozen.symbol().matches("^[A-Z0-9._-]{2,30}$")
                || frozen.featureAsOf() == null || frozen.latestFeatureAvailableAt() == null
                || frozen.featureAsOf().isAfter(Instant.now())
                || frozen.latestFeatureAvailableAt().isAfter(frozen.featureAsOf())
                || frozen.features() == null || frozen.features().size() != 6
                || frozen.features().entrySet().stream().anyMatch(e ->
                    e.getKey() == null || !e.getKey().matches("^[a-zA-Z0-9_.-]{2,128}$")
                    || e.getValue() == null || !Double.isFinite(e.getValue())
                    || Math.abs(e.getValue()) > 1e12)) {
            throw new IntelligenceUnavailableException("Frozen causal features are not eligible");
        }

        // Only server-side frozen fields are sent. No labels, user credentials,
        // caller-provided file paths, future outcomes or inferred source rights.
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("model_version_id", modelVersionId.toString());
        request.put("case_key", frozen.caseKey());
        request.put("symbol", frozen.symbol());
        request.put("source_input_fingerprint", frozen.sourceInputFingerprint());
        request.put("feature_as_of", frozen.featureAsOf().toString());
        request.put("latest_feature_available_at",
            frozen.latestFeatureAvailableAt().toString());
        request.put("features", new TreeMap<>(frozen.features()));
        final String requestFingerprint;
        final byte[] payload;
        try {
            requestFingerprint = sha256(canonicalJson(mapper.valueToTree(request)));
            payload = mapper.writeValueAsBytes(request);
        } catch (IOException e) {
            throw new IntelligenceUnavailableException("Research inference encoding failed");
        }
        if (payload.length > 16_384) {
            throw new IntelligenceUnavailableException("Research inference payload exceeds bound");
        }

        final byte[] bytes;
        try {
            bytes = transport.post(endpoint, payload, token);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IntelligenceUnavailableException("Research inference outcome UNKNOWN");
        } catch (IOException e) {
            // Never resubmit on timeout/connection loss. The worker may have scored.
            throw new IntelligenceUnavailableException("Research inference outcome UNKNOWN");
        }
        if (bytes == null || bytes.length == 0 || bytes.length > 32_768) {
            throw new IntelligenceUnavailableException("Research inference response size invalid");
        }

        try {
            JsonNode node = mapper.readTree(bytes);
            if (!node.isObject()) {
                throw new IllegalArgumentException("Unexpected research inference document");
            }
            List<String> fields = new ArrayList<>();
            node.fieldNames().forEachRemaining(fields::add);
            if (!RESPONSE_FIELDS.equals(Set.copyOf(fields))
                    || !CONTRACT.equals(text(node, "contract_version"))
                    || !modelVersionId.toString().equals(text(node, "model_version_id"))
                    || !frozen.caseKey().equals(text(node, "case_key"))
                    || !frozen.symbol().equals(text(node, "symbol"))
                    || !frozen.sourceInputFingerprint().equals(
                        text(node, "source_input_fingerprint"))
                    || !requestFingerprint.equals(text(node, "feature_payload_fingerprint"))
                    || !node.path("independent_input_custody_verified").isBoolean()
                    || node.path("independent_input_custody_verified").booleanValue()
                    || !node.path("model_promotion_allowed").isBoolean()
                    || node.path("model_promotion_allowed").booleanValue()
                    || !node.path("can_authorize_capital").isBoolean()
                    || node.path("can_authorize_capital").booleanValue()
                    || !"ACTUAL_MODEL_COMPUTATION_RESEARCH_ONLY".equals(text(node, "status"))) {
                throw new IllegalArgumentException("Invalid research worker provenance or authority");
            }
            String family = text(node, "model_family");
            String action = text(node, "action");
            String artifact = text(node, "model_artifact_fingerprint");
            String fingerprint = text(node, "response_fingerprint");
            if (!Set.of("CATBOOST", "LIGHTGBM").contains(family)
                    || !Set.of("ENTER", "WAIT", "ABSTAIN").contains(action)
                    || !artifact.matches(HASH) || !fingerprint.matches(HASH)
                    || !node.path("probability").isNumber()) {
                throw new IllegalArgumentException("Research scoring output invalid");
            }
            double probability = node.get("probability").doubleValue();
            Instant evaluatedAt = Instant.parse(text(node, "evaluated_at"));
            if (!Double.isFinite(probability) || probability < 0 || probability > 1
                    || evaluatedAt.isBefore(frozen.featureAsOf())
                    || evaluatedAt.isAfter(Instant.now().plusSeconds(5))) {
                throw new IllegalArgumentException("Research response outside causal bounds");
            }
            var withoutHash = ((com.fasterxml.jackson.databind.node.ObjectNode) node).deepCopy();
            withoutHash.remove("response_fingerprint");
            String expected = sha256(canonicalJson(withoutHash));
            if (!MessageDigest.isEqual(expected.getBytes(StandardCharsets.US_ASCII),
                    fingerprint.getBytes(StandardCharsets.US_ASCII))) {
                throw new IllegalArgumentException("Research response fingerprint mismatch");
            }
            return new Result(modelVersionId, family, artifact, frozen.caseKey(),
                frozen.symbol(), frozen.sourceInputFingerprint(), requestFingerprint,
                evaluatedAt, probability, action, fingerprint, false);
        } catch (Exception e) {
            // No remote response bodies or headers are disclosed.
            throw new IntelligenceUnavailableException("Research worker evidence rejected");
        }
    }

    private static URI validatedEndpoint(String raw) {
        try {
            URI uri = URI.create(raw);
            String host = uri.getHost();
            boolean loopback = host != null && Set.of(
                "localhost", "127.0.0.1", "[::1]", "::1").contains(host);
            if (host == null || uri.getUserInfo() != null || uri.getRawQuery() != null
                    || uri.getRawFragment() != null
                    || !(uri.getRawPath() == null || uri.getRawPath().isEmpty()
                        || "/".equals(uri.getRawPath()))
                    || !("https".equals(uri.getScheme())
                        || ("http".equals(uri.getScheme()) && loopback))) {
                throw new IllegalArgumentException("Invalid internal research worker URL");
            }
            return uri.resolve(PATH);
        } catch (RuntimeException e) {
            throw new IntelligenceUnavailableException("Research worker endpoint is unavailable");
        }
    }

    private static String text(JsonNode node, String field) {
        JsonNode child = node.get(field);
        if (child == null || !child.isTextual()) {
            throw new IllegalArgumentException("Missing research evidence field");
        }
        return child.textValue();
    }

    // Python PR #400 uses json.dumps(sort_keys=True, separators=(',', ':'), ensure_ascii=True).
    private byte[] canonicalJson(JsonNode node) throws IOException {
        return mapper.writeValueAsBytes(canonicalValue(node));
    }

    private static Object canonicalValue(JsonNode node) {
        if (node.isObject()) {
            Map<String, Object> sorted = new TreeMap<>();
            node.fields().forEachRemaining(e ->
                sorted.put(e.getKey(), canonicalValue(e.getValue())));
            return sorted;
        }
        if (node.isArray()) {
            List<Object> values = new ArrayList<>();
            node.forEach(v -> values.add(canonicalValue(v)));
            return values;
        }
        if (node.isNumber()) return node.numberValue();
        if (node.isBoolean()) return node.booleanValue();
        if (node.isNull()) return null;
        return node.textValue();
    }

    private static String sha256(byte[] data) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(data);
            return java.util.HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 unavailable");
        }
    }
}
