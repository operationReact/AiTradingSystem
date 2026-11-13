package in.aitradingsystem.canonical;

import static org.junit.jupiter.api.Assertions.*;

import java.math.BigDecimal;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class CanonicalCandleInputTest {
    private static final Instant EVENT = Instant.parse("2024-01-02T09:00:00Z");
    private static final Instant AVAILABLE = EVENT.plusSeconds(5);
    private static final Instant ACQUIRED = EVENT.plusSeconds(10);

    private CanonicalCandleInput.Candle sample(String provider, CanonicalCandleInput.Rights rights) {
        return new CanonicalCandleInput.Candle("NSE:ABC", "listing-1", "NSE", "1m",
                provider, "dataset-v1", EVENT, ACQUIRED, AVAILABLE,
                new BigDecimal("100"), new BigDecimal("110"), new BigDecimal("90"),
                new BigDecimal("105"), 100, "sha256:synthetic", "fixture-only", rights);
    }

    @Test void equivalentProviderFixturesShareInputSemantics() {
        var rights = new CanonicalCandleInput.Rights(
                CanonicalCandleInput.Permission.VERIFIED, CanonicalCandleInput.Permission.VERIFIED,
                CanonicalCandleInput.Permission.UNKNOWN, CanonicalCandleInput.Permission.DENIED,
                "synthetic-grant-fixture");
        var angel = sample("ANGEL_ONE", rights);
        var upstox = sample("UPSTOX", rights);
        assertEquals(angel.open(), upstox.open());
        assertEquals(angel.availableAt(), upstox.availableAt());
        assertTrue(angel.eligible(CanonicalCandleInput.Use.RESEARCH, AVAILABLE));
        assertTrue(upstox.eligible(CanonicalCandleInput.Use.RESEARCH, AVAILABLE));
        assertFalse(angel.eligible(CanonicalCandleInput.Use.RESEARCH, EVENT));
        assertFalse(upstox.eligible(CanonicalCandleInput.Use.TRAIN, ACQUIRED));
        assertFalse(upstox.eligible(CanonicalCandleInput.Use.DISPLAY, ACQUIRED));
    }

    @Test void unknownRightsFailClosedEvenAfterAvailability() {
        var rights = new CanonicalCandleInput.Rights(
                CanonicalCandleInput.Permission.UNKNOWN, CanonicalCandleInput.Permission.UNKNOWN,
                CanonicalCandleInput.Permission.UNKNOWN, CanonicalCandleInput.Permission.UNKNOWN, null);
        assertFalse(sample("UPSTOX", rights).eligible(CanonicalCandleInput.Use.RESEARCH, ACQUIRED));
    }

    @Test void invalidPriceAndLookaheadAreRejected() {
        var rights = new CanonicalCandleInput.Rights(
                CanonicalCandleInput.Permission.UNKNOWN, CanonicalCandleInput.Permission.UNKNOWN,
                CanonicalCandleInput.Permission.UNKNOWN, CanonicalCandleInput.Permission.UNKNOWN, null);
        assertThrows(IllegalArgumentException.class, () -> new CanonicalCandleInput.Candle(
                "NSE:ABC", "listing-1", "NSE", "1m", "UPSTOX", "v1",
                EVENT, ACQUIRED, AVAILABLE, new BigDecimal("100"), new BigDecimal("95"),
                new BigDecimal("90"), new BigDecimal("105"), 100, "checksum", "fixture", rights));
        assertThrows(IllegalArgumentException.class, () -> new CanonicalCandleInput.Candle(
                "NSE:ABC", "listing-1", "NSE", "1m", "UPSTOX", "v1",
                EVENT, EVENT, AVAILABLE, new BigDecimal("100"), new BigDecimal("110"),
                new BigDecimal("90"), new BigDecimal("105"), 100, "checksum", "fixture", rights));
    }

    @Test void verifiedPermissionRequiresEvidenceReference() {
        assertThrows(IllegalArgumentException.class, () -> new CanonicalCandleInput.Rights(
                CanonicalCandleInput.Permission.VERIFIED, CanonicalCandleInput.Permission.UNKNOWN,
                CanonicalCandleInput.Permission.UNKNOWN, CanonicalCandleInput.Permission.UNKNOWN, null));
    }
}
