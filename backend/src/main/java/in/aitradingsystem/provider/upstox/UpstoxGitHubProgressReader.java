package in.aitradingsystem.provider.upstox;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * Strictly read-only fixed-repository GitHub telemetry. GitHub is never a
 * dependency of Upstox market-data ingestion or Mind inference.
 */
@Component
public class UpstoxGitHubProgressReader {
    private static final String ROOT = "https://api.github.com/repos/operationReact/AiTradingSystem";
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final int MAX_RESPONSE = 256 * 1024;
    private final RestClient http;
    private final String token;

    @org.springframework.beans.factory.annotation.Autowired
    public UpstoxGitHubProgressReader(@Value("${MINTTICK_GITHUB_READ_TOKEN:}") String token) {
        this(token, http());
    }

    UpstoxGitHubProgressReader(String token, RestClient client) {
        this.token = token == null ? "" : token.trim();
        this.http = client;
    }

    private static RestClient http() {
        var factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(4_000);
        factory.setReadTimeout(7_000);
        return RestClient.builder().baseUrl(ROOT).requestFactory(factory).build();
    }

    public record Result(long checked, long verified, long issues,
                         Map<String, Object> details) {}

    public Result inspect(Instant now) {
        try {
            JsonNode prs = get("/pulls?state=all&sort=updated&direction=desc&per_page=40");
            JsonNode runs = get("/actions/runs?per_page=15");
            return summarize(prs, runs, now);
        } catch (RuntimeException exception) {
            // Upstream HTTP exceptions never escape to the scheduler or UI.
            throw new IllegalStateException("GITHUB_PROGRESS_UNAVAILABLE");
        }
    }

    private JsonNode get(String path) {
        try {
            var request = http.get().uri(path)
                    .header(HttpHeaders.USER_AGENT, "MintTick-ReadOnly-Progress")
                    .header("X-GitHub-Api-Version", "2022-11-28")
                    .accept(MediaType.parseMediaType("application/vnd.github+json"));
            if (!token.isBlank()) request = request.header(HttpHeaders.AUTHORIZATION, "Bearer " + token);
            String value = request.retrieve().body(String.class);
            if (value == null || value.length() > MAX_RESPONSE) {
                throw new IllegalStateException("GITHUB_RESPONSE_INVALID");
            }
            return JSON.readTree(value);
        } catch (Exception ignored) {
            throw new IllegalStateException("GITHUB_READ_UNAVAILABLE");
        }
    }

    /** Pure parser with bounded, recent-only samples; does not claim all-time PR totals. */
    static Result summarize(JsonNode prs, JsonNode runs, Instant now) {
        if (now == null || prs == null || !prs.isArray() || prs.size() > 40
                || runs == null || !runs.isObject()
                || !runs.path("workflow_runs").isArray()
                || runs.path("workflow_runs").size() > 15) {
            throw new IllegalArgumentException("Invalid GitHub telemetry response");
        }
        long open = 0, merged = 0, failedCi = 0, passedCi = 0;
        List<Map<String, Object>> tracked = new ArrayList<>();
        for (JsonNode pr : prs) {
            int number = pr.path("number").asInt(-1);
            String title = pr.path("title").asText("");
            String state = pr.path("state").asText("");
            String label = title.toLowerCase(Locale.ROOT);
            if (number <= 0 || number > 1_000_000 || title.length() > 180
                    || !("open".equals(state) || "closed".equals(state))) {
                throw new IllegalArgumentException("Untrusted PR payload");
            }
            if (!label.contains("upstox") && !label.contains("mintick mind")
                    && !label.contains("analytics") && !label.contains("canonical")) {
                continue;
            }
            if ("open".equals(state)) open++;
            else if (!pr.path("merged_at").isNull() && !pr.path("merged_at").isMissingNode()) merged++;
            if (tracked.size() < 12) {
                String status = "open".equals(state) ? "OPEN"
                        : !pr.path("merged_at").isNull() && !pr.path("merged_at").isMissingNode()
                        ? "MERGED" : "CLOSED";
                tracked.add(Map.of("number", number, "title", title, "state", status,
                        "url", "https://github.com/operationReact/AiTradingSystem/pull/" + number));
            }
        }
        for (JsonNode run : runs.path("workflow_runs")) {
            String status = run.path("status").asText("");
            String conclusion = run.path("conclusion").asText("");
            if ("completed".equals(status) && "success".equals(conclusion)) passedCi++;
            if ("completed".equals(status) && "failure".equals(conclusion)) failedCi++;
        }
        var details = Map.<String, Object>of(
                "repo", "operationReact/AiTradingSystem",
                "branch", "dev",
                "scope", "RECENT_SAMPLE_NOT_TOTAL",
                "openPrs", open, "mergedPrs", merged,
                "recentCiPassed", passedCi, "recentCiFailed", failedCi,
                "trackedPrs", tracked,
                "asOf", now.toString());
        return new Result(open + merged, merged, failedCi, details);
    }
}
