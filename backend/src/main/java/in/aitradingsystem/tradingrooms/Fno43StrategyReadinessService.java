package in.aitradingsystem.tradingrooms;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.InputStream;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import org.springframework.stereotype.Service;

/**
 * A read-only 43-strategy *readiness* registry, not a 43-strategy execution engine.
 * The authoritative manifest supplies identities and distinct required inputs.
 * STR-170..180 have distinct research evaluator calls; STR-180 needs original-time near-close spot/future pair and carry/delivery receipts.
 * STR-181/182 have a signal route, NOT a verified call to their distinct evaluators.
 * No research status, including RESEARCH_CANDIDATE, authorizes PAPER/LIVE orders.
 */
@Service
public final class Fno43StrategyReadinessService {
    private static final Set<String> DIRECT =
        Set.of("strategy.STR-170", "strategy.STR-171", "strategy.STR-172", "strategy.STR-173", "strategy.STR-174", "strategy.STR-175", "strategy.STR-176", "strategy.STR-177", "strategy.STR-178", "strategy.STR-179", "strategy.STR-180");
    private static final Set<String> SIGNAL_ONLY =
        Set.of("strategy.STR-181", "strategy.STR-182");
    private static final String MANIFEST = "/mind/capability-manifest.v1.json";

    private record Definition(String id, String name, String source, List<String> requiredInputs) {}

    public record StrategyRow(String strategyId, String name, String evaluatorSource,
                              List<String> requiredInputs, String integrationState,
                              String evaluatorStatus, String reasonCode,
                              Instant evaluatedAt, String quoteSource,
                              int contractsResolved, int freshQuotes, int completedHistorySessions,
                              boolean distinctEvaluatorObserved,
                              boolean independentlyVerifiedSourceRights,
                              boolean historicalDataE2EValidated,
                              boolean paperExecutionValidated, boolean liveExecutionValidated) {}

    public record Coverage(String contractVersion, Instant generatedAt, int manifestStrategies,
                           int distinctEvaluatorsObserved, int signalRoutesWithoutDistinctEvaluator,
                           int strategiesWithoutDirectEvaluatorAdapter,
                           int independentlyVerifiedSourceStrategies,
                           int historicalDataE2EValidated,
                           int paperExecutionValidated, int liveExecutionValidated,
                           List<StrategyRow> strategies) {}

    private final FnoFuturesResearchService futures;
    private final List<Definition> definitions;

    public Fno43StrategyReadinessService(FnoFuturesResearchService futures, ObjectMapper mapper) {
        this.futures = Objects.requireNonNull(futures);
        this.definitions = readManifest(Objects.requireNonNull(mapper));
    }

    /** Never persists inputs, touches broker order services or grants permissions. */
    public Coverage inspect(Instant now) {
        Objects.requireNonNull(now);
        Map<String, FnoFuturesResearchService.StrategyStatus> observed = new HashMap<>();
        try {
            List<FnoFuturesResearchService.StrategyStatus> futuresRows = futures.evaluateAll(now);
            if (futuresRows != null) for (var status : futuresRows) {
                if (status != null && DIRECT.contains(status.strategyId())
                        && status.status() != null && status.reasonCode() != null) {
                    // Conflicting/duplicate runtime evidence is treated as unavailable.
                    if (observed.putIfAbsent(status.strategyId(), status) != null) {
                        observed.clear();
                        break;
                    }
                }
            }
        } catch (RuntimeException unavailable) {
            // Fail closed. Do not expose provider exceptions or token-bearing messages.
            observed.clear();
        }

        List<StrategyRow> rows = new ArrayList<>(definitions.size());
        for (var definition : definitions) {
            var runtime = observed.get(definition.id());
            String state;
            String reason;
            if (DIRECT.contains(definition.id())) {
                state = runtime == null ? "DIRECT_ADAPTER_UNAVAILABLE" : "DIRECT_EVALUATOR_OBSERVED";
                reason = runtime == null ? "RUNTIME_EVALUATION_EVIDENCE_MISSING" : runtime.reasonCode();
            } else if (SIGNAL_ONLY.contains(definition.id())) {
                state = "SIGNAL_ROUTE_ONLY";
                reason = "DISTINCT_EVALUATOR_NOT_INVOKED";
            } else {
                state = "NO_TYPED_SOURCE_ADAPTER";
                reason = "DIRECT_EVALUATOR_ADAPTER_NOT_CONNECTED";
            }
            rows.add(new StrategyRow(
                definition.id(), definition.name(), definition.source(), definition.requiredInputs(),
                state, runtime == null ? "NOT_EVALUATED" : runtime.status(), reason,
                runtime == null ? null : runtime.evaluatedAt(),
                runtime == null ? "NONE" : runtime.quoteSource(),
                runtime == null ? 0 : runtime.contractsResolved(),
                runtime == null ? 0 : runtime.freshQuotes(),
                runtime == null ? 0 : runtime.historySessions(),
                runtime != null,
                false, false, false, false));
        }
        return new Coverage(
            "minttick.fno43.runtime-source-readiness.v1", now, definitions.size(),
            observed.size(), SIGNAL_ONLY.size(), definitions.size() - DIRECT.size(),
            0, 0, 0, 0, List.copyOf(rows));
    }

    private static List<Definition> readManifest(ObjectMapper mapper) {
        TreeMap<Integer, Definition> selected = new TreeMap<>();
        try (InputStream input = Fno43StrategyReadinessService.class.getResourceAsStream(MANIFEST)) {
            if (input == null) throw new IllegalStateException("FNO43_MANIFEST_MISSING");
            JsonNode entries = mapper.readTree(input).path("items");
            if (!entries.isArray()) throw new IllegalStateException("FNO43_MANIFEST_ITEMS_INVALID");
            for (JsonNode item : entries) {
                String id = item.path("id").asText("");
                if (!id.matches("strategy\\.STR-\\d{3}")) continue;
                int numericId = Integer.parseInt(id.substring(id.length() - 3));
                if (numericId < 170 || numericId > 212) continue;
                if (!"DATA_BLOCKED".equals(item.path("status").asText())
                        || !"JAVA_DETERMINISTIC".equals(
                                item.path("implementation").path("runtime").asText())) {
                    throw new IllegalStateException("FNO43_UNREVIEWED_STRATEGY_STATUS");
                }
                List<String> inputs = new ArrayList<>();
                for (JsonNode required : item.path("requiredInputs")) {
                    if (required.isTextual() && !required.asText().isBlank()) {
                        inputs.add(required.asText());
                    }
                }
                String source = "";
                for (JsonNode proof : item.path("evidence")) {
                    if ("SOURCE".equals(proof.path("kind").asText())) {
                        source = proof.path("path").asText("");
                        break;
                    }
                }
                String name = item.path("name").asText("");
                if (inputs.isEmpty() || name.isBlank() || !source.startsWith(
                        "backend/src/main/java/in/aitradingsystem/tradingrooms/Fno")) {
                    throw new IllegalStateException("FNO43_STRATEGY_INPUT_CONTRACT_INCOMPLETE");
                }
                if (selected.putIfAbsent(numericId,
                        new Definition(id, name, source, List.copyOf(inputs))) != null) {
                    throw new IllegalStateException("FNO43_DUPLICATE_STRATEGY");
                }
            }
        } catch (IOException e) {
            throw new IllegalStateException("FNO43_MANIFEST_UNREADABLE", e);
        }
        if (selected.size() != 43) throw new IllegalStateException("FNO43_MANIFEST_NOT_43");
        for (int n = 170; n <= 212; n++) {
            if (!selected.containsKey(n)) throw new IllegalStateException("FNO43_MANIFEST_GAP");
        }
        return List.copyOf(selected.values());
    }
}
