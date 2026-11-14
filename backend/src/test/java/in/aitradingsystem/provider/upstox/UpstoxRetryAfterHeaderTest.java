package in.aitradingsystem.provider.upstox;

import static org.junit.jupiter.api.Assertions.*;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

class UpstoxRetryAfterHeaderTest {
    private static final Instant RECEIVED = Instant.parse("2026-11-14T00:00:00Z");

    @Test void parsesSecondsAndPreservesCooldownBoundary() {
        var receipt = UpstoxRetryAfterHeader.receipt("60", RECEIVED);
        assertEquals(Duration.ofSeconds(60), receipt.retryAfter().orElseThrow());
        assertEquals(UpstoxSourceCooldownGate.State.COOLING_DOWN,
                UpstoxSourceCooldownGate.evaluate(receipt, Clock.fixed(RECEIVED.plusSeconds(59), ZoneOffset.UTC)));
        assertEquals(UpstoxSourceCooldownGate.State.ELAPSED,
                UpstoxSourceCooldownGate.evaluate(receipt, Clock.fixed(RECEIVED.plusSeconds(60), ZoneOffset.UTC)));
    }

    @Test void parsesHttpDateRelativeToReceiptTime() {
        assertEquals(Duration.ofSeconds(120),
                UpstoxRetryAfterHeader.parse("Sat, 14 Nov 2026 00:02:00 GMT", RECEIVED).orElseThrow());
    }

    @Test void pastHttpDateIsImmediatelyElapsed() {
        assertEquals(Duration.ZERO,
                UpstoxRetryAfterHeader.parse("Fri, 13 Nov 2026 23:59:00 GMT", RECEIVED).orElseThrow());
    }

    @Test void missingMalformedNegativeAndExcessiveValuesFailClosed() {
        for (String header : new String[]{null, "", "  ", "-1", "1.5", "NaN", "999999999999999999999", "86401",
                "Sun, 15 Nov 2026 00:00:01 GMT", "Saturday, 14 Nov 2026"}) {
            var receipt = UpstoxRetryAfterHeader.receipt(header, RECEIVED);
            assertEquals(UpstoxSourceCooldownGate.State.BLOCKED_UNKNOWN_COOLDOWN,
                    UpstoxSourceCooldownGate.evaluate(receipt, Clock.fixed(RECEIVED.plusSeconds(100000), ZoneOffset.UTC)));
        }
    }
}
