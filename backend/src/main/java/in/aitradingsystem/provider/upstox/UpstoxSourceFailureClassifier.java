package in.aitradingsystem.provider.upstox;

import java.time.Duration;
import java.util.Optional;

/** Provider-only sanitized failure evidence; never includes URLs, tokens or response bodies.
 * Classification alone never authorizes retry, persistence or ingestion.
 */
public final class UpstoxSourceFailureClassifier {
    private UpstoxSourceFailureClassifier() {}

    public enum Code {
        RATE_LIMITED, UNAUTHORIZED, FORBIDDEN, NOT_FOUND,
        UPSTREAM_UNAVAILABLE, UPSTREAM_REJECTED, TRANSPORT_FAILURE
    }

    public record Failure(Code code, Optional<Duration> cooldown, boolean retryCandidate) {
        public Failure {
            if (code == null || cooldown == null) throw new IllegalArgumentException("Missing evidence");
        }
    }

    /** Only a separately validated Retry-After seconds value may be recorded. */
    public static Failure http(int status, Long retryAfterSeconds) {
        if (status < 400 || status > 599) throw new IllegalArgumentException("Not an HTTP failure");
        if (status == 429) {
            Optional<Duration> cooldown = retryAfterSeconds != null
                    && retryAfterSeconds >= 0 && retryAfterSeconds <= 86400
                    ? Optional.of(Duration.ofSeconds(retryAfterSeconds)) : Optional.empty();
            return new Failure(Code.RATE_LIMITED, cooldown, false);
        }
        if (status == 401) return new Failure(Code.UNAUTHORIZED, Optional.empty(), false);
        if (status == 403) return new Failure(Code.FORBIDDEN, Optional.empty(), false);
        if (status == 404) return new Failure(Code.NOT_FOUND, Optional.empty(), false);
        if (status >= 500) return new Failure(Code.UPSTREAM_UNAVAILABLE, Optional.empty(), true);
        return new Failure(Code.UPSTREAM_REJECTED, Optional.empty(), false);
    }

    public static Failure transport() {
        return new Failure(Code.TRANSPORT_FAILURE, Optional.empty(), true);
    }
}
