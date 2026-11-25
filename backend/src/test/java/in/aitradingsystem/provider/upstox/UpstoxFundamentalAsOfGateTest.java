package in.aitradingsystem.provider.upstox;

import static org.junit.jupiter.api.Assertions.assertEquals;
import java.time.Instant;
import java.util.Set;
import org.junit.jupiter.api.Test;

class UpstoxFundamentalAsOfGateTest {
    private static final Instant T = Instant.parse("2026-11-14T09:00:00Z");
    private static UpstoxFundamentalAsOfGate.Evidence evidence(Set<UpstoxFundamentalAsOfGate.Purpose> purposes,
            Instant published, Instant available, Instant validUntil, String listing) {
        return new UpstoxFundamentalAsOfGate.Evidence("source-opaque", "rights-opaque",
                T.minusSeconds(3600), validUntil, purposes, "instrument", listing, published, available);
    }
    private static void check(UpstoxFundamentalAsOfGate.Decision expected,
            UpstoxFundamentalAsOfGate.Evidence e, UpstoxFundamentalAsOfGate.Purpose p) {
        assertEquals(expected, UpstoxFundamentalAsOfGate.assess(e, p, T));
    }
    @Test void missingEvidenceAndNoGrantDeny() {
        check(UpstoxFundamentalAsOfGate.Decision.RIGHTS_UNVERIFIED, null,
                UpstoxFundamentalAsOfGate.Purpose.RETAIN);
        check(UpstoxFundamentalAsOfGate.Decision.RIGHTS_UNVERIFIED,
                evidence(Set.of(), T.minusSeconds(60), T.minusSeconds(30), T.plusSeconds(60), "episode"),
                UpstoxFundamentalAsOfGate.Purpose.RETAIN);
    }
    @Test void transientReadDoesNotAuthorizeRetentionTrainingOrPublication() {
        var e = evidence(Set.of(UpstoxFundamentalAsOfGate.Purpose.READ),
                T.minusSeconds(60), T.minusSeconds(30), T.plusSeconds(60), "episode");
        check(UpstoxFundamentalAsOfGate.Decision.ALLOW, e, UpstoxFundamentalAsOfGate.Purpose.READ);
        for (var p : Set.of(UpstoxFundamentalAsOfGate.Purpose.RETAIN,
                UpstoxFundamentalAsOfGate.Purpose.TRAIN, UpstoxFundamentalAsOfGate.Purpose.PUBLISH))
            check(UpstoxFundamentalAsOfGate.Decision.RIGHTS_UNVERIFIED, e, p);
    }
    @Test void expiryIsExclusive() {
        check(UpstoxFundamentalAsOfGate.Decision.RIGHTS_EXPIRED,
                evidence(Set.of(UpstoxFundamentalAsOfGate.Purpose.RETAIN),
                        T.minusSeconds(60), T.minusSeconds(30), T, "episode"),
                UpstoxFundamentalAsOfGate.Purpose.RETAIN);
    }
    @Test void missingOrFutureAvailabilityIsNotAsOfSafe() {
        var p = UpstoxFundamentalAsOfGate.Purpose.RETAIN;
        check(UpstoxFundamentalAsOfGate.Decision.MISSING_AVAILABILITY,
                evidence(Set.of(p), T.minusSeconds(60), null, T.plusSeconds(60), "episode"), p);
        check(UpstoxFundamentalAsOfGate.Decision.LOOKAHEAD,
                evidence(Set.of(p), T.minusSeconds(60), T.plusSeconds(1), T.plusSeconds(60), "episode"), p);
        check(UpstoxFundamentalAsOfGate.Decision.LOOKAHEAD,
                evidence(Set.of(p), T.minusSeconds(30), T.minusSeconds(60), T.plusSeconds(60), "episode"), p);
    }
    @Test void listingMustBeResolved() {
        var p = UpstoxFundamentalAsOfGate.Purpose.RETAIN;
        check(UpstoxFundamentalAsOfGate.Decision.UNRESOLVED_LISTING,
                evidence(Set.of(p), T.minusSeconds(60), T.minusSeconds(30), T.plusSeconds(60), null), p);
    }
}
