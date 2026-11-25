package in.aitradingsystem.provider.upstox;

import java.time.Instant;
import java.util.Objects;

/**
 * Provider-side diagnostic for fundamental/event observation timestamps.
 * Does not authorize storage, research, model training, or endpoint access.
 * Null evidence means unobserved, never a successful zero-record response.
 */
public final class UpstoxFundamentalObservationAudit {
    private UpstoxFundamentalObservationAudit() {}

    public enum Result { UNOBSERVED, INVALID_SEQUENCE, FUTURE_EVIDENCE, STALE, RECENT_OBSERVATION }

    public record Observation(Instant attemptedAt, Instant respondedAt, Instant evaluatedAt,
                              long maxAgeSeconds, Integer observedCount, boolean responseAccepted) {
        public Observation {
            Objects.requireNonNull(evaluatedAt, "evaluatedAt");
            if (maxAgeSeconds < 0 || maxAgeSeconds > 86_400)
                throw new IllegalArgumentException("maxAgeSeconds must be bounded");
            if (observedCount != null && observedCount < 0)
                throw new IllegalArgumentException("negative count");
            if (!responseAccepted && observedCount != null)
                throw new IllegalArgumentException("failed or unknown response cannot have a count");
            if (responseAccepted && observedCount == null)
                throw new IllegalArgumentException("accepted response needs an observed count");
        }
    }

    public static Result evaluate(Observation evidence) {
        Objects.requireNonNull(evidence, "evidence");
        if (evidence.attemptedAt() == null && evidence.respondedAt() == null
                && evidence.observedCount() == null && !evidence.responseAccepted())
            return Result.UNOBSERVED;
        if (evidence.attemptedAt() == null || evidence.respondedAt() == null
                || evidence.respondedAt().isBefore(evidence.attemptedAt()))
            return Result.INVALID_SEQUENCE;
        if (evidence.attemptedAt().isAfter(evidence.evaluatedAt())
                || evidence.respondedAt().isAfter(evidence.evaluatedAt()))
            return Result.FUTURE_EVIDENCE;
        if (!evidence.responseAccepted()) return Result.INVALID_SEQUENCE;
        if (evidence.respondedAt().isBefore(evidence.evaluatedAt().minusSeconds(evidence.maxAgeSeconds())))
            return Result.STALE;
        return Result.RECENT_OBSERVATION;
    }
}
