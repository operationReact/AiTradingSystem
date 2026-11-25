package in.aitradingsystem.canonical.fo;

import static org.junit.jupiter.api.Assertions.*;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;

class CanonicalFoReceiptConsumerV1Test {
    private static final Instant NOW = Instant.parse("2026-11-16T04:00:00Z");
    private static final LocalDate SESSION = LocalDate.of(2026, 11, 16);
    private static final Duration MAX_AGE = Duration.ofSeconds(10);

    private CanonicalFoContractV1 contract(boolean rights) {
        return new CanonicalFoContractV1("NSE_FO|TEST_CE", "NSE_INDEX|TEST",
            "NSE_FO", LocalDate.of(2026, 11, 17), new BigDecimal("25000"),
            CanonicalFoContractV1.OptionSide.CE, 65, new BigDecimal("0.05"),
            "UPSTOX", NOW.minusSeconds(4), NOW.minusSeconds(3), NOW.minusSeconds(2),
            true, rights, rights, false, false);
    }

    private CanonicalFoReceiptConsumerV1.Receipt receipt(boolean rights, boolean tick) {
        return new CanonicalFoReceiptConsumerV1.Receipt("TEST_RECEIPT", "UPSTOX",
            "NSE_FO|TEST_CE", "NSE_INDEX|TEST", LocalDate.of(2026, 11, 17),
            new BigDecimal("25000"), CanonicalFoContractV1.OptionSide.CE, 65,
            new BigDecimal("0.05"), true, rights, rights, false, false, false,
            tick, tick ? NOW.minusSeconds(2) : null, NOW.minusSeconds(1), NOW);
    }

    private CanonicalFoReceiptConsumerV1.ReadOnlyResult check(boolean rights, boolean tick) {
        return CanonicalFoReceiptConsumerV1.evaluate(contract(rights), receipt(rights, tick),
            NOW, SESSION, MAX_AGE);
    }

    @Test void unknownReceiptAndUnverifiedRightsFailClosed() {
        assertEquals(CanonicalFoReceiptConsumerV1.Status.UNKNOWN,
            CanonicalFoReceiptConsumerV1.evaluate(contract(false), null, NOW, SESSION, MAX_AGE).status());
        var denied = check(false, false);
        assertEquals(CanonicalFoReceiptConsumerV1.Status.RIGHTS_DENIED, denied.status());
        assertFalse(denied.privateResearchEligible());
        assertFalse(denied.simulationEligible());
    }

    @Test void receiptChainMetadataIsNotPerContractV3Tick() {
        assertEquals(CanonicalFoReceiptConsumerV1.Status.TICK_NOT_OBSERVED, check(true, false).status());
    }

    @Test void independentIdentityLotTickExpiryAndSourceGates() {
        var base = receipt(true, true);
        var wrongLot = new CanonicalFoReceiptConsumerV1.Receipt(base.receiptId(), base.source(),
            base.instrumentKey(), base.underlyingListingId(), base.expiry(), base.strike(),
            base.side(), 75, base.tickSize(), true, true, true, false, false, false,
            true, base.exchangeTickAt(), base.acquiredAt(), base.availableAt());
        assertEquals(CanonicalFoReceiptConsumerV1.Status.IDENTITY_MISMATCH,
            CanonicalFoReceiptConsumerV1.evaluate(contract(true), wrongLot, NOW, SESSION, MAX_AGE).status());
        assertEquals(CanonicalFoReceiptConsumerV1.Status.LISTING_UNVERIFIED,
            CanonicalFoReceiptConsumerV1.evaluate(contract(true), base, NOW,
                LocalDate.of(2026, 11, 18), MAX_AGE).status());
    }

    @Test void asOfAndFreshnessAndSimulationRightsAreIndependent() {
        var observed = check(true, true);
        assertEquals(CanonicalFoReceiptConsumerV1.Status.QUOTE_OBSERVED, observed.status());
        assertTrue(observed.privateResearchEligible());
        assertFalse(observed.simulationEligible());
        assertFalse(observed.retentionEligible());
        assertFalse(observed.trainingEligible());
        assertEquals(CanonicalFoReceiptConsumerV1.Status.STALE,
            CanonicalFoReceiptConsumerV1.evaluate(contract(true), receipt(true, true),
                NOW.plusSeconds(20), SESSION, MAX_AGE).status());
        assertEquals(CanonicalFoReceiptConsumerV1.Status.UNKNOWN,
            CanonicalFoReceiptConsumerV1.evaluate(contract(true), receipt(true, true),
                NOW.minusSeconds(1), SESSION, MAX_AGE).status());
    }
}
