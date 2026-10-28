package in.aitradingsystem.marketdata;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

/**
 * Deterministic market-data freshness classification used at provider boundaries.
 * Future timestamps and missing observations fail closed instead of being treated as fresh.
 */
public final class FeedFreshnessPolicy {

    public enum Status {
        FRESH,
        STALE,
        UNKNOWN
    }

    private final Duration maximumAge;

    public FeedFreshnessPolicy(Duration maximumAge) {
        Objects.requireNonNull(maximumAge, "maximumAge");
        if (maximumAge.isNegative() || maximumAge.isZero()) {
            throw new IllegalArgumentException("maximumAge must be positive");
        }
        this.maximumAge = maximumAge;
    }

    public Status classify(Instant observedAt, Instant evaluatedAt) {
        if (observedAt == null || evaluatedAt == null || observedAt.isAfter(evaluatedAt)) {
            return Status.UNKNOWN;
        }
        Duration age = Duration.between(observedAt, evaluatedAt);
        return age.compareTo(maximumAge) <= 0 ? Status.FRESH : Status.STALE;
    }

    public Duration maximumAge() {
        return maximumAge;
    }
}
