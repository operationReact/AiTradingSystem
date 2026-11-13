package in.aitradingsystem.provider.upstox;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Safe interpretation of GitHub issue #1012 worker reports.
 * A report is development evidence, never proof of a running ingestion service.
 * The caller must fetch comments read-only and supply their GitHub createdAt.
 */
public final class UpstoxWorkerReportLedger {
    public enum State { NO_REPORT, FRESH, STALE }
    public record Comment(String body, Instant createdAt) {}
    public record Report(int worker, String scope, String result, String pr,
                         String head, String ci, String runtime, String blocker,
                         String next, Instant reportedAt, State state) {}
    private static final String MARKER = "MINTTICK_UPSTOX_WORKER_V1";
    private static final String[] SCOPES = {
        "", "SOURCE_INGESTION", "CANONICAL_DATA", "COVERAGE_GAPS",
        "FUNDAMENTALS_EVENTS", "OPERATIONS_UI"
    };

    private UpstoxWorkerReportLedger() {}

    public static Map<Integer, Optional<Report>> latest(
            List<Comment> comments, Instant now, Duration maxAge) {
        if (comments == null || now == null || maxAge == null ||
                maxAge.isZero() || maxAge.isNegative()) {
            throw new IllegalArgumentException("invalid report query");
        }
        Map<Integer, Optional<Report>> result = new java.util.LinkedHashMap<>();
        for (int i = 1; i <= 5; i++) result.put(i, Optional.empty());
        for (Comment comment : comments) {
            Optional<Report> parsed = parse(comment, now, maxAge);
            if (parsed.isEmpty()) continue;
            Report report = parsed.get();
            Optional<Report> previous = result.get(report.worker());
            if (previous.isEmpty() || report.reportedAt().isAfter(previous.get().reportedAt())) {
                result.put(report.worker(), parsed);
            }
        }
        return java.util.Collections.unmodifiableMap(result);
    }

    public static Optional<Report> parse(Comment comment, Instant now, Duration maxAge) {
        if (comment == null || comment.body() == null || comment.createdAt() == null ||
                now == null || maxAge == null || maxAge.isNegative() || maxAge.isZero() ||
                comment.createdAt().isAfter(now)) return Optional.empty();
        String[] lines = comment.body().split("\\R");
        if (lines.length < 10 || !MARKER.equals(lines[0].trim())) return Optional.empty();
        Map<String, String> fields = new java.util.HashMap<>();
        for (int i = 1; i < 10; i++) {
            int split = lines[i].indexOf('=');
            if (split <= 0 || split == lines[i].length() - 1) return Optional.empty();
            String key = lines[i].substring(0, split).trim();
            String value = lines[i].substring(split + 1).trim();
            if (value.length() > 160 || fields.putIfAbsent(key, value) != null) return Optional.empty();
        }
        // Human notes may follow the fixed report header, but another machine field
        // must not override or ambiguously contradict the validated audit evidence.
        for (int i = 10; i < lines.length; i++) {
            String trailing = lines[i].trim();
            int separator = trailing.indexOf('=');
            if (separator > 0 && java.util.Set.of("worker", "scope", "result", "pr",
                    "head", "ci", "runtime", "blocker", "next")
                    .contains(trailing.substring(0, separator).trim())) return Optional.empty();
        }
        if (!fields.keySet().equals(java.util.Set.of("worker", "scope", "result", "pr",
                "head", "ci", "runtime", "blocker", "next"))) return Optional.empty();
        // Allow trailing human explanation but reject a repeated structured
        // field that would otherwise let a second, contradictory value escape.
        for (int i = 10; i < lines.length; i++) {
            String trailing = lines[i].trim();
            int equalsAt = trailing.indexOf('=');
            if (equalsAt > 0 && fields.containsKey(trailing.substring(0, equalsAt).trim())) {
                return Optional.empty();
            }
        }
        int worker;
        try { worker = Integer.parseInt(fields.get("worker")); }
        catch (NumberFormatException ex) { return Optional.empty(); }
        if (worker < 1 || worker > 5 || !SCOPES[worker].equals(fields.get("scope"))) return Optional.empty();
        if (!java.util.Set.of("MERGED", "PR_OPEN", "BLOCKED", "NO_CHANGE").contains(fields.get("result")) ||
            !java.util.Set.of("PASS", "FAIL", "PENDING", "NOT_RUN").contains(fields.get("ci")) ||
            !java.util.Set.of("NOT_OBSERVED", "VERIFIED").contains(fields.get("runtime")) ||
            !fields.get("pr").matches("NONE|[1-9][0-9]*") ||
            !fields.get("head").matches("NONE|[a-fA-F0-9]{40}") ||
            !fields.get("blocker").matches("[A-Z0-9_]+")) return Optional.empty();
        State state = Duration.between(comment.createdAt(), now).compareTo(maxAge) > 0
                ? State.STALE : State.FRESH;
        return Optional.of(new Report(worker, fields.get("scope"), fields.get("result"),
                fields.get("pr"), fields.get("head"), fields.get("ci"),
                fields.get("runtime"), fields.get("blocker"), fields.get("next"),
                comment.createdAt(), state));
    }
}
