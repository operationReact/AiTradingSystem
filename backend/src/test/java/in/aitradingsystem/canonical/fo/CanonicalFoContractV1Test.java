package in.aitradingsystem.canonical.fo;

import static org.junit.jupiter.api.Assertions.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;

class CanonicalFoContractV1Test {
    private static final Instant ACQUIRED = Instant.parse("2026-11-14T09:00:00Z");
    private static final Instant AVAILABLE = Instant.parse("2026-11-14T09:00:01Z");

    private CanonicalFoContractV1 contract(boolean listed, boolean read, boolean display,
                                          boolean retain, boolean train) {
        return new CanonicalFoContractV1("NSE_FO|SYNTHETIC", "NSE_EQ|SYNTHETIC",
            "NSE_FO", LocalDate.parse("2026-11-26"), new BigDecimal("25000"),
            CanonicalFoContractV1.OptionSide.CE, 65, new BigDecimal("0.05"),
            "SYNTHETIC_FIXTURE", null, ACQUIRED, AVAILABLE,
            listed, read, display, retain, train);
    }

    @Test void acceptsOnlyExplicitRightsAndAsOf() {
        var c = contract(true, true, true, false, false);
        assertFalse(c.eligibleForPrivatePaperResearch(ACQUIRED, LocalDate.parse("2026-11-16")));
        assertTrue(c.eligibleForPrivatePaperResearch(AVAILABLE, LocalDate.parse("2026-11-16")));
        assertFalse(c.eligibleForTraining(AVAILABLE, LocalDate.parse("2026-11-16")));
        assertFalse(c.eligibleForPrivatePaperResearch(AVAILABLE, LocalDate.parse("2026-11-27")));
        assertFalse(contract(false, true, true, true, true).eligibleForPrivatePaperResearch(AVAILABLE, LocalDate.parse("2026-11-16")));
        assertFalse(contract(true, false, true, true, true).eligibleForPrivatePaperResearch(AVAILABLE, LocalDate.parse("2026-11-16")));
        assertFalse(contract(true, true, false, true, true).eligibleForPrivatePaperResearch(AVAILABLE, LocalDate.parse("2026-11-16")));
        assertTrue(contract(true, true, true, true, true).eligibleForTraining(AVAILABLE, LocalDate.parse("2026-11-16")));
    }

    @Test void rejectsInvalidIdentityAndMetadata() {
        var c = contract(false, false, false, false, false);
        assertThrows(IllegalArgumentException.class, () -> new CanonicalFoContractV1(
            "NSE_EQ|INVALID", c.underlyingListingId(), c.exchange(), c.expiry(),
            c.strike(), c.side(), c.lotSize(), c.tickSize(), c.source(),
            c.observedAt(), c.acquiredAt(), c.availableAt(), false, false, false, false, false));
        assertThrows(IllegalArgumentException.class, () -> new CanonicalFoContractV1(
            c.instrumentKey(), c.underlyingListingId(), c.exchange(), c.expiry(),
            c.strike(), c.side(), 0, c.tickSize(), c.source(),
            c.observedAt(), c.acquiredAt(), c.availableAt(), false, false, false, false, false));
        assertThrows(IllegalArgumentException.class, () -> new CanonicalFoContractV1(
            c.instrumentKey(), c.underlyingListingId(), c.exchange(), c.expiry(),
            c.strike(), c.side(), c.lotSize(), c.tickSize(), c.source(),
            c.observedAt(), AVAILABLE, ACQUIRED, false, false, false, false, false));
    }
}
