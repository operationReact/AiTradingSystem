package in.aitradingsystem.canonical;

import static org.junit.jupiter.api.Assertions.*;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class CanonicalCandleWindowQualityTest {
    private static final Instant START = Instant.parse("2024-01-02T04:00:00Z");
    private static final Duration MINUTE = Duration.ofMinutes(1);

    private CanonicalCandleInput.Candle bar(String provider, int minute,
                                            CanonicalCandleInput.Permission permission) {
        var rights = new CanonicalCandleInput.Rights(permission, permission,
                CanonicalCandleInput.Permission.UNKNOWN, CanonicalCandleInput.Permission.UNKNOWN,
                permission == CanonicalCandleInput.Permission.VERIFIED ? "synthetic-fixture-only" : null);
        var at = START.plus(MINUTE.multipliedBy(minute));
        return new CanonicalCandleInput.Candle("NSE:TEST", "episode-1", "NSE", "1m",
                provider, "dataset-v1", at, at.plusSeconds(10), at.plusSeconds(5),
                new BigDecimal("100"), new BigDecimal("110"), new BigDecimal("90"),
                new BigDecimal("105"), 12, "sha256:fixture", "synthetic-only", rights);
    }

    private CanonicalCandleWindowQuality.Assessment check(List<CanonicalCandleInput.Candle> bars,
                                                            Instant asOf) {
        return CanonicalCandleWindowQuality.assess(bars, MINUTE, asOf,
                CanonicalCandleInput.Use.RESEARCH);
    }

    @Test void bothProvidersHaveSameValidatedContractButSeparatePartitions() {
        for (String provider : List.of("ANGEL_ONE", "UPSTOX")) {
            var result = check(List.of(bar(provider, 0, CanonicalCandleInput.Permission.VERIFIED),
                    bar(provider, 1, CanonicalCandleInput.Permission.VERIFIED)), START.plusSeconds(120));
            assertEquals(CanonicalCandleWindowQuality.Status.VALIDATED, result.status());
            assertEquals(2, result.eligibleRows());
        }
        assertEquals(CanonicalCandleWindowQuality.Status.MIXED_PARTITION,
                check(List.of(bar("UPSTOX", 0, CanonicalCandleInput.Permission.VERIFIED),
                        bar("ANGEL_ONE", 1, CanonicalCandleInput.Permission.VERIFIED)),
                        START.plusSeconds(120)).status());
    }

    @Test void duplicateOutOfOrderAndMissingSlotsAbstain() {
        var a = bar("UPSTOX", 0, CanonicalCandleInput.Permission.VERIFIED);
        var b = bar("UPSTOX", 1, CanonicalCandleInput.Permission.VERIFIED);
        var c = bar("UPSTOX", 3, CanonicalCandleInput.Permission.VERIFIED);
        var asOf = START.plusSeconds(300);
        assertEquals(CanonicalCandleWindowQuality.Status.DUPLICATE, check(List.of(a, a), asOf).status());
        assertEquals(CanonicalCandleWindowQuality.Status.OUT_OF_ORDER, check(List.of(b, a), asOf).status());
        var gap = check(List.of(a, c), asOf);
        assertEquals(CanonicalCandleWindowQuality.Status.GAP, gap.status());
        assertEquals(2, gap.missingSlots());
        assertFalse(gap.usable());
    }

    @Test void pointInTimeAndUnknownRightsFailClosed() {
        var a = bar("ANGEL_ONE", 0, CanonicalCandleInput.Permission.VERIFIED);
        assertEquals(CanonicalCandleWindowQuality.Status.NOT_AVAILABLE,
                check(List.of(a), START).status());
        assertEquals(CanonicalCandleWindowQuality.Status.RIGHTS_DENIED,
                check(List.of(bar("UPSTOX", 0, CanonicalCandleInput.Permission.UNKNOWN)),
                        START.plusSeconds(120)).status());
        assertEquals(CanonicalCandleWindowQuality.Status.EMPTY,
                check(List.of(), START.plusSeconds(120)).status());
    }

    @Test void invalidExpectedSpacingIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> CanonicalCandleWindowQuality.assess(
                List.of(), Duration.ZERO, START, CanonicalCandleInput.Use.RESEARCH));
    }
}
