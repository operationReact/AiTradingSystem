package in.aitradingsystem.marketdata;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/**
 * Read-only handoff of observed coverage failures. Does not fetch, schedule or persist bars.
 * Null counters mean unobserved, never zero; rights are evaluated by the ingestion owner.
 */
public final class UpstoxCoverageRetryCandidates {
    private UpstoxCoverageRetryCandidates() {}

    public record Observation(String canonicalInstrumentId, String listingEpisodeId,
            String interval, int year, int month, Instant windowStart, Instant windowEndExclusive,
            String source, String datasetVersion, Integer expectedSlots, Integer verifiedSlots,
            String reason, Instant lastAttemptAt, Instant cooldownUntil) {
        public Observation {
            if (blank(canonicalInstrumentId) || blank(listingEpisodeId) || blank(interval)
                    || blank(source) || blank(datasetVersion) || blank(reason)
                    || year < 1900 || month < 1 || month > 12
                    || windowStart == null || windowEndExclusive == null
                    || !windowStart.isBefore(windowEndExclusive)
                    || (expectedSlots != null && expectedSlots < 0)
                    || (verifiedSlots != null && verifiedSlots < 0)
                    || (expectedSlots != null && verifiedSlots != null && verifiedSlots > expectedSlots))
                throw new IllegalArgumentException("Invalid coverage observation");
        }
    }

    public record Candidate(Observation observation, String disposition) {}
    public record Page(List<Candidate> items, int total, int offset, boolean hasMore) {}

    public static Page select(List<Observation> observations, Instant asOf, int offset, int limit) {
        Objects.requireNonNull(observations, "observations");
        Objects.requireNonNull(asOf, "asOf");
        if (offset < 0 || limit < 1 || limit > 200) throw new IllegalArgumentException("Invalid page");
        List<Candidate> candidates = observations.stream()
                .map(o -> {
                    Objects.requireNonNull(o, "observation");
                    // Do not disclose future evidence to an as-of consumer.
                    if (o.windowEndExclusive().isAfter(asOf) || (o.lastAttemptAt() != null && o.lastAttemptAt().isAfter(asOf))) return null;
                    if ("RIGHTS_DENIED".equals(o.reason()) || "RIGHTS_UNKNOWN".equals(o.reason()))
                        return new Candidate(o, "BLOCKED_RIGHTS");
                    if (o.cooldownUntil() != null && o.cooldownUntil().isAfter(asOf))
                        return new Candidate(o, "COOLDOWN");
                    if (o.expectedSlots() == null || o.verifiedSlots() == null)
                        return new Candidate(o, "UNKNOWN_COVERAGE");
                    if (o.verifiedSlots() < o.expectedSlots() || "HTTP_429".equals(o.reason())
                            || "SOURCE_ERROR".equals(o.reason()))
                        return new Candidate(o, "REVIEW_RETRY");
                    return null;
                })
                .filter(Objects::nonNull)
                .sorted(Comparator.comparing((Candidate x) -> x.observation().canonicalInstrumentId())
                        .thenComparing(x -> x.observation().listingEpisodeId())
                        .thenComparing(x -> x.observation().interval())
                        .thenComparing(x -> x.observation().windowStart())
                        .thenComparing(x -> x.observation().source())
                        .thenComparing(x -> x.observation().datasetVersion())
                        .thenComparing(x -> x.observation().windowEndExclusive())
                        .thenComparing(x -> x.observation().reason()))
                .toList();
        int start = Math.min(offset, candidates.size());
        int end = (int) Math.min((long) start + limit, candidates.size());
        return new Page(List.copyOf(candidates.subList(start, end)), candidates.size(),
                offset, end < candidates.size());
    }

    private static boolean blank(String value) { return value == null || value.isBlank(); }
}
