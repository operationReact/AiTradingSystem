package in.aitradingsystem.provider.upstox;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/**
 * Source-local admission decision for a persisted HTTP 429 receipt.
 * A missing/invalid Retry-After is a blocker, never permission to retry.
 * This does not grant API, retention or training rights.
 */
public final class UpstoxSourceCooldownGate {
    private UpstoxSourceCooldownGate() {}

    public record Receipt(Instant observedAt, Optional<Duration> retryAfter) {
        public Receipt {
            Objects.requireNonNull(observedAt);
            Objects.requireNonNull(retryAfter);
            retryAfter.ifPresent(d -> {
                if (d.isNegative() || d.compareTo(Duration.ofDays(1)) > 0)
                    throw new IllegalArgumentException("Invalid source cooldown");
            });
        }
    }

    public enum State { BLOCKED_UNKNOWN_COOLDOWN, COOLING_DOWN, ELAPSED }

    public static State evaluate(Receipt receipt, Clock clock) {
        Objects.requireNonNull(receipt);
        Objects.requireNonNull(clock);
        if (receipt.retryAfter().isEmpty()) return State.BLOCKED_UNKNOWN_COOLDOWN;
        Instant eligibleAt;
        try {
            eligibleAt = receipt.observedAt().plus(receipt.retryAfter().orElseThrow());
        } catch (RuntimeException ex) {
            return State.BLOCKED_UNKNOWN_COOLDOWN;
        }
        return clock.instant().isBefore(eligibleAt) ? State.COOLING_DOWN : State.ELAPSED;
    }
}
