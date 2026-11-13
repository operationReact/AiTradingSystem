package in.aitradingsystem.provider.upstox;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

/**
 * Read-only, bounded GitHub issue #1012 worker-report observations.
 * ChatGPT automation execution cannot be established from a GitHub comment:
 * these are developer delivery notes, NEVER persisted ingestion evidence.
 */
@Component
public class UpstoxWorkerReportReader {
    private static final String ROOT = "https://api.github.com/repos/operationReact/AiTradingSystem";
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final int MAX_RESPONSE = 384 * 1024;
    private final RestClient http;
    private final String token;

    @Autowired
    public UpstoxWorkerReportReader(@Value("${MINTTICK_GITHUB_READ_TOKEN:}") String token) {
        this(token, defaultClient());
    }

    UpstoxWorkerReportReader(String token, RestClient http) {
        this.token = token == null ? "" : token.trim();
        this.http = http;
    }

    private static RestClient defaultClient() {
        var factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(4_000);
        factory.setReadTimeout(8_000);
        return RestClient.builder().requestFactory(factory).build();
    }

    public record Result(long reports, long fresh, Map<String, Object> details) {}

    /** Poll just 12 hours of reports, so pagination never looks like all-time worker activity. */
    public Result inspect(Instant now) {
        if (now == null) throw new IllegalArgumentException("Observation timestamp required");
        String path = "/issues/1012/comments?per_page=100&since="
                + now.minus(Duration.ofHours(12)).toString();
        try {
            var request = http.get().uri(URI.create(ROOT + path))
                    .header(HttpHeaders.USER_AGENT, "MintTick-Upstox-ReadOnly-Worker-Reports")
                    .header("X-GitHub-Api-Version", "2022-11-28")
                    .accept(MediaType.parseMediaType("application/vnd.github+json"));
            if (!token.isBlank()) request = request.header(HttpHeaders.AUTHORIZATION, "Bearer " + token);
            String response = request.retrieve().body(String.class);
            if (response == null || response.length() > MAX_RESPONSE) {
                throw new IllegalStateException("GITHUB_WORKER_REPORT_UNAVAILABLE");
            }
            return summarize(JSON.readTree(response), now);
        } catch (RestClientResponseException | ResourceAccessException | JsonProcessingException exception) {
            throw new IllegalStateException("GITHUB_WORKER_REPORT_UNAVAILABLE");
        }
    }

    static Result summarize(JsonNode comments, Instant now) {
        if (comments == null || !comments.isArray() || comments.size() > 100 || now == null) {
            throw new IllegalArgumentException("Invalid worker report sample");
        }
        List<UpstoxWorkerReportLedger.Comment> reportComments = new ArrayList<>();
        for (JsonNode entry : comments) {
            if (!"operationReact".equals(entry.path("user").path("login").asText(""))) continue;
            String body = entry.path("body").asText("");
            if (body.length() > 4096 || !body.startsWith("MINTTICK_UPSTOX_WORKER_V1")) continue;
            Instant created;
            try { created = Instant.parse(entry.path("created_at").asText()); }
            catch (RuntimeException ignored) { continue; }
            if (created.isAfter(now) || created.isBefore(now.minus(Duration.ofHours(12)))) continue;
            reportComments.add(new UpstoxWorkerReportLedger.Comment(body, created));
        }
        Map<Integer, java.util.Optional<UpstoxWorkerReportLedger.Report>> latest =
                UpstoxWorkerReportLedger.latest(reportComments, now, Duration.ofHours(2));
        List<Map<String, Object>> rows = new ArrayList<>();
        long fresh = 0, observed = 0;
        String[] scopes = {"", "SOURCE_INGESTION", "CANONICAL_DATA", "COVERAGE_GAPS",
                "FUNDAMENTALS_EVENTS", "OPERATIONS_UI"};
        for (int worker = 1; worker <= 5; worker++) {
            var found = latest.get(worker);
            var row = new LinkedHashMap<String, Object>();
            row.put("worker", worker);
            row.put("scope", scopes[worker]);
            if (found.isEmpty()) {
                row.put("state", "NO_REPORT");
            } else {
                var report = found.orElseThrow();
                observed++;
                if (report.state() == UpstoxWorkerReportLedger.State.FRESH) fresh++;
                row.put("state", report.state().name());
                row.put("result", report.result());
                row.put("pr", report.pr());
                row.put("ci", report.ci());
                row.put("blocker", report.blocker());
                row.put("next", report.next());
                row.put("reportedAt", report.reportedAt().toString());
                // Self-reported value is not independent proof of production activity.
                row.put("runtimeClaim", report.runtime());
                if (!"NONE".equals(report.pr())) {
                    row.put("url", "https://github.com/operationReact/AiTradingSystem/pull/"
                            + report.pr());
                }
            }
            rows.add(Map.copyOf(row));
        }
        Map<String, Object> details = Map.of(
                "workerReports", List.copyOf(rows),
                "reportSource", "GITHUB_ISSUE_1012",
                "scope", "GITHUB_REPORTS_NOT_AUTOMATION_OR_INGESTION_HEALTH",
                "reportWindowHours", 12,
                "reportsObserved", observed,
                "freshWorkers", fresh);
        return new Result(observed, fresh, details);
    }
}
