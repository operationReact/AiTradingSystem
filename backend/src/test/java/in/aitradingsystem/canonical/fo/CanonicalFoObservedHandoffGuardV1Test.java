package in.aitradingsystem.canonical.fo;

import static org.junit.jupiter.api.Assertions.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;

class CanonicalFoObservedHandoffGuardV1Test {
    private static final Instant OBSERVED = Instant.parse("2026-11-14T09:00:00Z");
    private static final Instant ACQUIRED = OBSERVED.plusSeconds(1);
    private static final Instant AVAILABLE = ACQUIRED.plusSeconds(1);
    private static final LocalDate EXPIRY = LocalDate.of(2026, 11, 17);
    private static final LocalDate SESSION = LocalDate.of(2026, 11, 16);

    // Synthetic per-contract economics. Receipt #43603258735 confirms aggregate
    // option-chain counts only; it does NOT verify these lot/tick values or rights.
    private CanonicalFoObservedMetadataGateV1.Observation observation(
            Integer lot, BigDecimal tick, boolean listing, boolean read, boolean display) {
        return new CanonicalFoObservedMetadataGateV1.Observation(
                "NSE_FO|SYNTH_NIFTY_CE", "NSE_INDEX|SYNTH_NIFTY", "NSE_FO",
                EXPIRY, new BigDecimal("25000"), CanonicalFoContractV1.OptionSide.CE,
                lot, tick, "SYNTHETIC_FIXTURE", OBSERVED, ACQUIRED, AVAILABLE,
                listing, read, display, false, false);
    }

    private CanonicalFoIdentityHandoffV1 consumer(
            LocalDate expiry, CanonicalFoContractV1.OptionSide side, int lot, BigDecimal tick) {
        return new CanonicalFoIdentityHandoffV1(
                "NSE_FO|SYNTH_NIFTY_CE", "NSE_INDEX|SYNTH_NIFTY", "NSE_FO",
                expiry, new BigDecimal("25000"), side, lot, tick, AVAILABLE);
    }

    private boolean allowed(CanonicalFoObservedMetadataGateV1.Observation observation,
                            CanonicalFoIdentityHandoffV1 consumer) {
        return CanonicalFoObservedHandoffGuardV1.eligibleForPrivatePaperResearch(
                observation, consumer, AVAILABLE, SESSION);
    }

    @Test void exactIdentityMatchesOnlyWithExplicitRights() {
        var consumer = consumer(EXPIRY, CanonicalFoContractV1.OptionSide.CE, 65, new BigDecimal("0.050"));
        assertTrue(allowed(observation(65, new BigDecimal("0.05"), true, true, true), consumer));
        assertFalse(allowed(observation(65, new BigDecimal("0.05"), false, true, true), consumer));
        assertFalse(allowed(observation(65, new BigDecimal("0.05"), true, false, true), consumer));
        assertFalse(allowed(observation(65, new BigDecimal("0.05"), true, true, false), consumer));
    }

    @Test void expirySideLotAndTickMismatchesReject() {
        var observed = observation(65, new BigDecimal("0.05"), true, true, true);
        assertFalse(allowed(observed, consumer(EXPIRY.plusDays(7), CanonicalFoContractV1.OptionSide.CE, 65, new BigDecimal("0.05"))));
        assertFalse(allowed(observed, consumer(EXPIRY, CanonicalFoContractV1.OptionSide.PE, 65, new BigDecimal("0.05"))));
        assertFalse(allowed(observed, consumer(EXPIRY, CanonicalFoContractV1.OptionSide.CE, 50, new BigDecimal("0.05"))));
        assertFalse(allowed(observed, consumer(EXPIRY, CanonicalFoContractV1.OptionSide.CE, 65, new BigDecimal("0.10"))));
    }

    @Test void missingEconomicsAndFutureAvailabilityFailClosed() {
        var consumer = consumer(EXPIRY, CanonicalFoContractV1.OptionSide.CE, 65, new BigDecimal("0.05"));
        assertFalse(allowed(observation(null, new BigDecimal("0.05"), true, true, true), consumer));
        assertFalse(allowed(observation(65, null, true, true, true), consumer));
        assertFalse(CanonicalFoObservedHandoffGuardV1.eligibleForPrivatePaperResearch(
                observation(65, new BigDecimal("0.05"), true, true, true),
                consumer, ACQUIRED, SESSION));
        assertFalse(CanonicalFoObservedHandoffGuardV1.eligibleForPrivatePaperResearch(
                observation(65, new BigDecimal("0.05"), true, true, true),
                consumer, AVAILABLE, EXPIRY.plusDays(1)));
        assertFalse(allowed(null, consumer));
        assertFalse(allowed(observation(65, new BigDecimal("0.05"), true, true, true), null));
    }
}
