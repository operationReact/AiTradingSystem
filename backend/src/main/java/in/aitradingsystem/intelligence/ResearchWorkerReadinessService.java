package in.aitradingsystem.intelligence;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import in.aitradingsystem.execution.TradingExecutionAuthority;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;

/**
 * V4-09/V72: live read-only worker artifact roster check against original
 * immutable Java V56 enrollment. A successful check is NOT deployment proof,
 * source licensing, PAPER soak, trading authority or permission to dispatch.
 *
 * No @Transactional across HTTP. The endpoint cannot reserve model requests,
 * append receipts, accept a caller-defined worker URL, or place an order.
 */
@Service
public class ResearchWorkerReadinessService {
    public static final String CONTRACT = "v4-research-worker-readiness.v1";
    private static final String WORKER_CONTRACT =
        "v4-research-worker-roster-readiness.v1";
    private static final String PATH = "/v1/research/artifact-readiness";
    private static final String HASH = "^[a-f0-9]{64}$";
    private static final Set<String> WORKER_FIELDS = Set.of(
        "contract_version", "manifest_sha256", "models",
        "local_artifacts_validated", "independently_verified_deployment",
        "source_rights_verified", "paper_shadow_soak_accepted",
        "broker_reconciliation_accepted", "can_authorize_capital",
        "response_fingerprint");
    private static final Set<String> MODEL_FIELDS = Set.of(
        "model_version_id", "model_family", "artifact_fingerprint");

    @FunctionalInterface
    interface Transport {
        byte[] get(URI endpoint, String token) throws IOException, InterruptedException;
    }

    private static final Transport HTTP = (endpoint, token) -> {
        var request = HttpRequest.newBuilder(endpoint)
            .version(HttpClient.Version.HTTP_1_1)
            .timeout(Duration.ofSeconds(5))
            .header("Accept", "application/json")
            .header("X-Research-Worker-Token", token)
            .GET().build();
        var response = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .followRedirects(HttpClient.Redirect.NEVER)
            .connectTimeout(Duration.ofSeconds(3))
            .build().send(request, HttpResponse.BodyHandlers.ofByteArray());
        if (response.statusCode() != 200) {
            // Do not disclose untrusted remote error bodies or auth tokens.
            throw new IOException("Worker readiness unavailable");
        }
        return response.body();
    };

    private record Roster(String state, String enrollmentFingerprint) {}
    private record Model(UUID versionId, String family, String artifactFingerprint,
        String versionFingerprint, String enrollmentVersionFingerprint) {}

    public record ReadinessView(
        String contractVersion, UUID sessionId, String sessionSafetyState,
        int expectedModels, int workerAllowlistedModels, int extraWorkerModels,
        String workerManifestFingerprint, String workerResponseFingerprint,
        String status, boolean workerRosterMatched,
        boolean localWorkerArtifactsValidated,
        boolean independentlyVerifiedDeployment,
        boolean sourceRightsVerified, boolean paperShadowSoakAccepted,
        boolean brokerReconciliationAccepted, boolean liveExecutionEnabled,
        boolean canAuthorizeCapital) {}

    private final JdbcClient jdbc;
    private final ObjectMapper mapper;
    private final TradingExecutionAuthority authority;
    private final String baseUrl;
    private final String token;
    private final String trustedHost;
    private final boolean workerEnabled;
    private final boolean enabled;
    private final Transport transport;

    @Autowired
    public ResearchWorkerReadinessService(JdbcClient jdbc, ObjectMapper mapper,
            TradingExecutionAuthority authority,
            @Value("${app.research.artifact-worker.base-url:}") String baseUrl,
            @Value("${app.research.artifact-worker.token:}") String token,
            @Value("${app.research.artifact-worker.enabled:false}") boolean workerEnabled,
            @Value("${app.research.worker-readiness.enabled:false}") boolean enabled,
            @Value("${app.research.artifact-worker.trusted-host:}") String trustedHost) {
        this(jdbc, mapper, authority, baseUrl, token, workerEnabled, enabled,
            trustedHost, HTTP);
    }

    // Package-private seam preserves historical localhost/synthetic tests.
    ResearchWorkerReadinessService(JdbcClient jdbc, ObjectMapper mapper,
            TradingExecutionAuthority authority, String baseUrl, String token,
            boolean workerEnabled, boolean enabled, Transport transport) {
        this(jdbc, mapper, authority, baseUrl, token, workerEnabled, enabled,
            ResearchWorkerTlsOrigin.syntheticTestPin(baseUrl), transport);
    }

    ResearchWorkerReadinessService(JdbcClient jdbc, ObjectMapper mapper,
            TradingExecutionAuthority authority, String baseUrl, String token,
            boolean workerEnabled, boolean enabled, String trustedHost,
            Transport transport) {
        this.jdbc = jdbc;
        this.mapper = mapper;
        this.authority = authority;
        this.baseUrl = baseUrl;
        this.token = token;
        this.trustedHost = trustedHost;
        this.workerEnabled = workerEnabled;
        this.enabled = enabled;
        this.transport = transport;
    }

    @PreAuthorize("hasRole('ADMIN')")
    public ReadinessView inspect(UUID sessionId) {
        if (!enabled || !workerEnabled || token == null || token.length() < 32) {
            throw new IllegalStateException("Research worker readiness is disabled");
        }
        if (sessionId == null) {
            throw new IllegalArgumentException("Original research session required");
        }
        if (!TradingExecutionAuthority.PAPER.equals(authority.current().tradingMode())) {
            throw new IllegalStateException("Research readiness requires PAPER mode");
        }
        URI url = validatedEndpoint(baseUrl);
        ResearchWorkerTlsOrigin.requirePinned(url, trustedHost);
        // All JDBC reads finish before any remote HTTP request.
        Roster header = jdbc.sql("""
                SELECT s.enrollment_fingerprint, st.state
                  FROM v4_matched_session s
                  JOIN v4_matched_session_state st
                    ON st.session_id=s.session_id
                 WHERE s.session_id=:id
                """)
            .param("id", sessionId)
            .query((rs, index) -> new Roster(rs.getString("state"),
                rs.getString("enrollment_fingerprint")))
            .optional()
            .orElseThrow(() -> new NoSuchElementException("Research session unavailable"));
        List<Model> roster = jdbc.sql("""
                SELECT p.model_version_id, d.family,
                       v.artifact_fingerprint, v.version_fingerprint,
                       p.model_fingerprint
                  FROM v4_matched_participant p
                  JOIN model_version v ON v.version_id=p.model_version_id
                  JOIN model_definition d ON d.definition_id=v.definition_id
                 WHERE p.session_id=:id ORDER BY p.model_version_id
                """)
            .param("id", sessionId)
            .query((rs, index) -> new Model(
                rs.getObject("model_version_id", UUID.class),
                rs.getString("family"),
                rs.getString("artifact_fingerprint"),
                rs.getString("version_fingerprint"),
                rs.getString("model_fingerprint")))
            .list();
        if (roster.size() < 2 || roster.size() > 10
                || !header.enrollmentFingerprint().matches(HASH)
                || roster.stream().anyMatch(model ->
                    !model.versionFingerprint().equals(
                        model.enrollmentVersionFingerprint())
                    || !model.artifactFingerprint().matches(HASH)
                    || !Set.of("CATBOOST", "LIGHTGBM").contains(model.family()))
                || roster.stream().map(Model::versionId).distinct().count()
                    != roster.size()) {
            throw new IllegalStateException("Original enrolled research roster invalid");
        }

        byte[] bytes;
        try {
            bytes = transport.get(url, token);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Research worker readiness unavailable");
        } catch (IOException error) {
            throw new IllegalStateException("Research worker readiness unavailable");
        }
        if (bytes == null || bytes.length == 0 || bytes.length > 16_384) {
            throw new IllegalStateException("Research worker response outside size bound");
        }
        try {
            JsonNode response = mapper.readTree(bytes);
            if (!response.isObject() || !fields(response).equals(WORKER_FIELDS)
                    || !WORKER_CONTRACT.equals(requiredText(response, "contract_version"))
                    || !requiredText(response, "manifest_sha256").matches(HASH)
                    || !requiredText(response, "response_fingerprint").matches(HASH)
                    || !literal(response, "local_artifacts_validated", true)
                    || !literal(response, "independently_verified_deployment", false)
                    || !literal(response, "source_rights_verified", false)
                    || !literal(response, "paper_shadow_soak_accepted", false)
                    || !literal(response, "broker_reconciliation_accepted", false)
                    || !literal(response, "can_authorize_capital", false)) {
                throw new IllegalStateException("Worker readiness attestation rejected");
            }
            var copy = ((com.fasterxml.jackson.databind.node.ObjectNode) response).deepCopy();
            copy.remove("response_fingerprint");
            String expectedHash = sha256(mapper.writeValueAsBytes(canonical(copy)));
            String returnedHash = requiredText(response, "response_fingerprint");
            if (!MessageDigest.isEqual(expectedHash.getBytes(StandardCharsets.US_ASCII),
                    returnedHash.getBytes(StandardCharsets.US_ASCII))) {
                throw new IllegalStateException("Worker readiness response fingerprint differs");
            }
            JsonNode rawModels = response.path("models");
            if (!rawModels.isArray() || rawModels.size() < 1 || rawModels.size() > 16) {
                throw new IllegalStateException("Worker model allowlist invalid");
            }
            Map<UUID, String[]> workerModels = new HashMap<>();
            for (JsonNode raw : rawModels) {
                if (!raw.isObject() || !MODEL_FIELDS.equals(fields(raw))) {
                    throw new IllegalStateException("Worker model metadata invalid");
                }
                UUID id = UUID.fromString(requiredText(raw, "model_version_id"));
                String family = requiredText(raw, "model_family");
                String artifact = requiredText(raw, "artifact_fingerprint");
                if (!Set.of("CATBOOST", "LIGHTGBM").contains(family)
                        || !artifact.matches(HASH)
                        || workerModels.putIfAbsent(id, new String[]{family, artifact})
                            != null) {
                    throw new IllegalStateException("Duplicate or invalid worker model");
                }
            }
            boolean matched = roster.stream().allMatch(model -> {
                String[] actual = workerModels.get(model.versionId());
                return actual != null && model.family().equals(actual[0])
                    && model.artifactFingerprint().equals(actual[1]);
            });
            Set<UUID> originalVersionIds = new HashSet<>();
            roster.forEach(model -> originalVersionIds.add(model.versionId()));
            int extraModels = (int) workerModels.keySet().stream()
                .filter(id -> !originalVersionIds.contains(id)).count();
            return new ReadinessView(CONTRACT, sessionId, header.state(),
                roster.size(), workerModels.size(), extraModels,
                requiredText(response, "manifest_sha256"), returnedHash,
                matched ? "MATCHED_RESEARCH_ONLY" : "MISMATCH_NO_DISPATCH_AUTHORITY",
                matched, true, false, false, false, false, false, false);
        } catch (IOException | IllegalArgumentException error) {
            throw new IllegalStateException("Worker readiness evidence invalid");
        }
    }

    private static String requiredText(JsonNode node, String key) {
        JsonNode value = node.get(key);
        if (value == null || !value.isTextual()) {
            throw new IllegalStateException("Missing worker readiness evidence field");
        }
        return value.textValue();
    }

    private static boolean literal(JsonNode node, String name, boolean value) {
        JsonNode candidate = node.path(name);
        return candidate.isBoolean() && candidate.booleanValue() == value;
    }

    private static Set<String> fields(JsonNode json) {
        Set<String> result = new HashSet<>();
        json.fieldNames().forEachRemaining(result::add);
        return result;
    }

    private static Object canonical(JsonNode json) {
        if (json.isObject()) {
            Map<String, Object> values = new TreeMap<>();
            json.fields().forEachRemaining(e -> values.put(e.getKey(), canonical(e.getValue())));
            return values;
        }
        if (json.isArray()) {
            List<Object> items = new ArrayList<>();
            json.forEach(item -> items.add(canonical(item)));
            return items;
        }
        if (json.isBoolean()) return json.booleanValue();
        if (json.isNumber()) return json.numberValue();
        if (json.isNull()) return null;
        return json.textValue();
    }

    private static String sha256(byte[] data) {
        try {
            return HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest(data));
        } catch (NoSuchAlgorithmException missing) {
            throw new IllegalStateException("Required SHA-256 unavailable");
        }
    }

    private static URI validatedEndpoint(String raw) {
        try {
            URI uri = URI.create(raw);
            String host = uri.getHost();
            boolean loopback = host != null && Set.of(
                "127.0.0.1", "localhost", "::1", "[::1]").contains(host);
            if (host == null || uri.getUserInfo() != null
                    || uri.getRawQuery() != null || uri.getRawFragment() != null
                    || !(uri.getRawPath() == null || uri.getRawPath().isBlank()
                        || "/".equals(uri.getRawPath()))
                    || !("https".equals(uri.getScheme())
                        || ("http".equals(uri.getScheme()) && loopback))) {
                throw new IllegalArgumentException("Invalid trusted research worker URL");
            }
            return uri.resolve(PATH);
        } catch (RuntimeException error) {
            throw new IllegalStateException("Research worker readiness endpoint invalid");
        }
    }
}
