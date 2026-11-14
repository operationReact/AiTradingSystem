package in.aitradingsystem.provider.upstox;

import static org.junit.jupiter.api.Assertions.*;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class UpstoxSourceCooldownGateTest {
    private static final Instant START = Instant.parse("2026-11-14T00:00:00Z");

    private static Clock at(long seconds) {
        return Clock.fixed(START.plusSeconds(seconds), ZoneOffset.UTC);
    }

    @Test void missingRetryAfterFailsClosed() {
        var receipt = new UpstoxSourceCooldownGate.Receipt(START, Optional.empty());
        assertEquals(UpstoxSourceCooldownGate.State.BLOCKED_UNKNOWN_COOLDOWN,
                UpstoxSourceCooldownGate.evaluate(receipt, at(90000)));
    }

    @Test void blocksBeforeDeadlineAndAllowsAtDeadline() {
        var receipt = new UpstoxSourceCooldownGate.Receipt(START, Optional.of(Duration.ofSeconds(60)));
        assertEquals(UpstoxSourceCooldownGate.State.COOLING_DOWN,
                UpstoxSourceCooldownGate.evaluate(receipt, at(59)));
        assertEquals(UpstoxSourceCooldownGate.State.ELAPSED,
                UpstoxSourceCooldownGate.evaluate(receipt, at(60)));
    }

    @Test void invalidDurationRejected() {
        assertThrows(IllegalArgumentException.class, () ->
                new UpstoxSourceCooldownGate.Receipt(START, Optional.of(Duration.ofSeconds(-1))));
        assertThrows(IllegalArgumentException.class, () ->
                new UpstoxSourceCooldownGate.Receipt(START, Optional.of(Duration.ofDays(2))));
    }

    @Test void timestampOverflowFailsClosed() {
        var receipt = new UpstoxSourceCooldownGate.Receipt(Instant.MAX, Optional.of(Duration.ofSeconds(1)));
        assertEquals(UpstoxSourceCooldownGate.State.BLOCKED_UNKNOWN_COOLDOWN,
                UpstoxSourceCooldownGate.evaluate(receipt, at(90000)));
    }
}
