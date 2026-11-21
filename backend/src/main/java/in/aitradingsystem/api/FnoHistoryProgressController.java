package in.aitradingsystem.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.DateTimeException;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.core.env.Environment;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * CUSTOMER-authenticated, strictly read-only expired NSE_FO checkpoint progress.
 * No raw market data, provider tokens, contract keys, or instrument prices.
 * Existing SecurityConfig requires ROLE_CUSTOMER on /api/customer/**.
 */
@RestController
@RequestMapping("/api/customer/fno-history")
public class FnoHistoryProgressController {
    private static final String SCHEMA = "minttick.fno.history.progress.v1";
    private static final String SOURCE = "upstox-expired-nse-fo";
    private static final String PLAN_SCOPE = "CURRENT_JOURNAL_BATCHES_ONLY";
    private static final String[] COUNTS = {
        "PENDING", "ACTIVE", "RETRY", "DONE", "EMPTY", "FAILED", "BLOCKED"
    };
    private final String snapshotPath;
    private final ObjectMapper json;

    public FnoHistoryProgressController(Environment environment, ObjectMapper json) {
        this.snapshotPath = environment.getProperty(
            "FNO_HISTORY_PROGRESS_SNAPSHOT_PATH", "").trim();
        this.json = json;
    }

    public record Progress(String schema, String source, String state,
                           String evidence, String generatedAt,
                           Integer totalBatches, Integer completedBatches,
                           Double completionPercent, Long rowsWritten,
                           String lastCompletedAt, Map<String, Integer> counts,
                           boolean retentionRightsVerified,
                           boolean storageActive, String planScope) {}

    private Progress unavailable(String state, String evidence) {
        return new Progress(SCHEMA, SOURCE, state, evidence, null,
            null, null, null, null, null, null, false, false, PLAN_SCOPE);
    }

    private static int count(JsonNode n) {
        if (n == null || !n.isIntegralNumber() || !n.canConvertToInt()
                || n.asInt() < 0 || n.asInt() > 1_000_000_000) {
            throw new IllegalArgumentException("INVALID_FNO_COUNT");
        }
        return n.asInt();
    }

    private static long rows(JsonNode n) {
        if (n == null || !n.isIntegralNumber() || !n.canConvertToLong()
                || n.asLong() < 0 || n.asLong() > 10_000_000_000L) {
            throw new IllegalArgumentException("INVALID_FNO_ROWS");
        }
        return n.asLong();
    }

    private static Instant timestamp(JsonNode n) {
        if (n == null || !n.isTextual() || n.asText().length() > 40) {
            throw new IllegalArgumentException("INVALID_FNO_TIMESTAMP");
        }
        return Instant.parse(n.asText());
    }

    @GetMapping("/progress")
    public Progress progress() {
        if (snapshotPath.isBlank()) {
            return unavailable("NOT_CONFIGURED", "NO_RUNTIME_CHECKPOINT_SNAPSHOT");
        }
        try {
            Path path = Path.of(snapshotPath);
            if (Files.isSymbolicLink(path) || !Files.isRegularFile(path)
                    || Files.size(path) < 16 || Files.size(path) > 4096) {
                return unavailable("UNAVAILABLE", "NO_VERIFIED_RUNTIME_SNAPSHOT");
            }
            JsonNode node = json.readTree(Files.readAllBytes(path));
            if (node == null || !SCHEMA.equals(node.path("schema").asText())
                    || !SOURCE.equals(node.path("source").asText())
                    || !PLAN_SCOPE.equals(node.path("planScope").asText())
                    || !"PREPARATION_ONLY".equals(node.path("state").asText())
                    || !node.path("retentionRightsVerified").isBoolean()
                    || node.path("retentionRightsVerified").asBoolean()
                    || !node.path("storageActive").isBoolean()
                    || node.path("storageActive").asBoolean()) {
                return unavailable("UNAVAILABLE", "INVALID_RUNTIME_SNAPSHOT");
            }
            Instant captured = timestamp(node.get("generatedAt"));
            Instant now = Instant.now();
            if (captured.isAfter(now.plus(Duration.ofMinutes(5)))) {
                return unavailable("UNAVAILABLE", "INVALID_RUNTIME_SNAPSHOT");
            }
            JsonNode rawCounts = node.path("counts");
            if (!rawCounts.isObject() || rawCounts.size() != COUNTS.length) {
                return unavailable("UNAVAILABLE", "INVALID_RUNTIME_SNAPSHOT");
            }
            var counts = new LinkedHashMap<String, Integer>();
            long sum = 0;
            for (String name : COUNTS) {
                int value = count(rawCounts.get(name));
                counts.put(name, value);
                sum += value;
            }
            JsonNode totalNode = node.get("totalBatches");
            int total = totalNode == null || totalNode.isNull() ? 0 : count(totalNode);
            if (sum != total) {
                return unavailable("UNAVAILABLE", "INVALID_RUNTIME_SNAPSHOT");
            }
            Integer completed = null;
            Double percent = null;
            Long written = null;
            if (total == 0) {
                if (!node.path("completedBatches").isNull()
                        || !node.path("completionPercent").isNull()
                        || !node.path("rowsWritten").isNull()) {
                    return unavailable("UNAVAILABLE", "INVALID_RUNTIME_SNAPSHOT");
                }
            } else {
                completed = count(node.get("completedBatches"));
                if (completed != counts.get("DONE") + counts.get("EMPTY")
                        || completed > total) {
                    return unavailable("UNAVAILABLE", "INVALID_RUNTIME_SNAPSHOT");
                }
                JsonNode pct = node.get("completionPercent");
                if (pct == null || !pct.isNumber()
                        || !Double.isFinite(pct.asDouble())) {
                    return unavailable("UNAVAILABLE", "INVALID_RUNTIME_SNAPSHOT");
                }
                percent = pct.asDouble();
                double expected = Math.round(10000.0 * completed / total) / 100.0;
                if (percent < 0 || percent > 100
                        || Math.abs(percent - expected) > 0.011) {
                    return unavailable("UNAVAILABLE", "INVALID_RUNTIME_SNAPSHOT");
                }
                written = rows(node.get("rowsWritten"));
            }
            String last = node.hasNonNull("lastCompletedAt")
                ? timestamp(node.get("lastCompletedAt")).toString() : null;
            boolean stale = captured.isBefore(now.minus(Duration.ofMinutes(10)));
            return new Progress(SCHEMA, SOURCE,
                stale ? "STALE" : "PREPARATION_ONLY",
                stale ? "SNAPSHOT_OUTDATED" : "CHECKPOINT_METADATA_ONLY",
                captured.toString(),
                total == 0 ? null : total, completed, percent, written, last,
                Map.copyOf(counts), false, false, PLAN_SCOPE);
        } catch (IOException | IllegalArgumentException | DateTimeException e) {
            // Never include local paths or untrusted JSON in a response.
            return unavailable("UNAVAILABLE", "INVALID_RUNTIME_SNAPSHOT");
        }
    }
}
