package in.aitradingsystem.canonical.fo;

import static org.junit.jupiter.api.Assertions.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;

class CanonicalFoObservedMetadataGateV1Test {
    private static final Instant OBSERVED = Instant.parse("2026-11-14T09:00:00Z");
    private static final Instant ACQUIRED = OBSERVED.plusSeconds(1);
    private static final Instant AVAILABLE = ACQUIRED.plusSeconds(1);
    private static final LocalDate EXPIRY = LocalDate.of(2026, 11, 17);
    private static final LocalDate SESSION = LocalDate.of(2026, 11, 16);

    private CanonicalFoObservedMetadataGateV1.Observation observation(
            Integer lot, BigDecimal tick, boolean listing, boolean read, boolean display) {
        // Synthetic contract economics; source observation #43603258735 supplies only aggregate
        // 284-contract/142-strike counts, NOT verified per-contract lot, tick or rights.
        return new CanonicalFoObservedMetadataGateV1.Observation(
                "NSE_FO|SYNTHETIC_NIFTY_CE", "NSE_INDEX|SYNTHETIC_NIFTY",
                "NSE_FO", EXPIRY, new BigDecimal("25000"),
                CanonicalFoContractV1.OptionSide.CE, lot, tick, "UPSTOX_OBSERVATION",
                OBSERVED, ACQUIRED, AVAILABLE, listing, read, display, false, false);
    }

    @Test void observedChainWithoutVerifiedLotAndTickFailsClosed() {
        var unknownLot = observation(null, new BigDecimal("0.05"), false, false, false);
        var unknownTick = observation(65, null, false, false, false);
        assertThrows(IllegalArgumentException.class, () -> CanonicalFoObservedMetadataGateV1.map(unknownLot));
        assertThrows(IllegalArgumentException.class, () -> CanonicalFoObservedMetadataGateV1.map(unknownTick));
        assertFalse(CanonicalFoObservedMetadataGateV1.permittedPrivateResearch(unknownLot, AVAILABLE, SESSION));
        assertFalse(CanonicalFoObservedMetadataGateV1.permittedPrivateResearch(unknownTick, AVAILABLE, SESSION));
    }

    @Test void structurallyMappedObservationDoesNotImplySourceRights() {
        var observed = observation(65, new BigDecimal("0.05"), false, false, false);
        var canonical = CanonicalFoObservedMetadataGateV1.map(observed);
        assertEquals("NSE_FO|SYNTHETIC_NIFTY_CE", canonical.instrumentKey());
        assertEquals(EXPIRY, canonical.expiry());
        assertEquals(65, canonical.lotSize());
        assertEquals(0, canonical.tickSize().compareTo(new BigDecimal("0.050")));
        assertFalse(CanonicalFoObservedMetadataGateV1.permittedPrivateResearch(observed, AVAILABLE, SESSION));
        assertFalse(canonical.eligibleForTraining(AVAILABLE, SESSION));
    }

    @Test void listingAndEachIndependentRightAreMandatory() {
        assertFalse(CanonicalFoObservedMetadataGateV1.permittedPrivateResearch(
                observation(65, new BigDecimal("0.05"), false, true, true), AVAILABLE, SESSION));
        assertFalse(CanonicalFoObservedMetadataGateV1.permittedPrivateResearch(
                observation(65, new BigDecimal("0.05"), true, false, true), AVAILABLE, SESSION));
        assertFalse(CanonicalFoObservedMetadataGateV1.permittedPrivateResearch(
                observation(65, new BigDecimal("0.05"), true, true, false), AVAILABLE, SESSION));
        var permitted = observation(65, new BigDecimal("0.05"), true, true, true);
        assertTrue(CanonicalFoObservedMetadataGateV1.permittedPrivateResearch(permitted, AVAILABLE, SESSION));
        assertFalse(CanonicalFoObservedMetadataGateV1.permittedPrivateResearch(permitted, ACQUIRED, SESSION));
        assertFalse(CanonicalFoObservedMetadataGateV1.permittedPrivateResearch(permitted, AVAILABLE, EXPIRY.plusDays(1)));
        assertFalse(CanonicalFoObservedMetadataGateV1.map(permitted).eligibleForTraining(AVAILABLE, SESSION));
    }

    @Test void invalidObservedExchangeAndTimestampReject() {
        var base = observation(65, new BigDecimal("0.05"), true, true, true);
        var invalid = new CanonicalFoObservedMetadataGateV1.Observation(
                base.instrumentKey(), base.underlyingListingId(), "NSE_EQ", base.expiry(),
                base.strike(), base.side(), base.lotSize(), base.tickSize(), base.source(),
                base.observedAt(), base.acquiredAt(), base.availableAt(), true, true, true, false, false);
        assertThrows(IllegalArgumentException.class, () -> CanonicalFoObservedMetadataGateV1.map(invalid));
        assertFalse(CanonicalFoObservedMetadataGateV1.permittedPrivateResearch(invalid, AVAILABLE, SESSION));
    }
}
