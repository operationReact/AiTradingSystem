package in.aitradingsystem.canonical.fo;

import static org.junit.jupiter.api.Assertions.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;

/**
 * Offline producer -> consumer contract tests. The values below are synthetic:
 * receipt #43603258735 proves aggregate chain counts, not contract economics,
 * per-contract ticks, exchange listing or any data-use grant.
 */
class CanonicalFoProducerConsumerBoundaryV1Test {
    private static final Instant OBSERVED = Instant.parse("2026-11-14T09:00:00Z");
    private static final Instant ACQUIRED = OBSERVED.plusSeconds(1);
    private static final Instant AVAILABLE = ACQUIRED.plusSeconds(1);
    private static final LocalDate EXPIRY = LocalDate.of(2026, 11, 17);
    private static final LocalDate SESSION = LocalDate.of(2026, 11, 16);

    private CanonicalFoObservedMetadataGateV1.Observation observed(
            CanonicalFoContractV1.OptionSide side, boolean listing, boolean read, boolean display) {
        return new CanonicalFoObservedMetadataGateV1.Observation(
                "NSE_FO|SYNTHETIC_NIFTY_" + side,
                "NSE_INDEX|SYNTHETIC_NIFTY", "NSE_FO", EXPIRY,
                new BigDecimal("25000"), side, 65, new BigDecimal("0.05"),
                "SYNTHETIC_FIXTURE", OBSERVED, ACQUIRED, AVAILABLE,
                listing, read, display, false, false);
    }

    private CanonicalFoIdentityHandoffV1 consumer(CanonicalFoContractV1 p) {
        return new CanonicalFoIdentityHandoffV1(p.instrumentKey(), p.underlyingListingId(),
                p.exchange(), p.expiry(), p.strike(), p.side(), p.lotSize(),
                p.tickSize(), p.availableAt());
    }

    @Test void exactCeAndPeIdentityMatchDoesNotGrantUnknownRights() {
        for (var side : CanonicalFoContractV1.OptionSide.values()) {
            var p = CanonicalFoObservedMetadataGateV1.map(observed(side, false, false, false));
            var c = consumer(p);
            assertTrue(c.matches(p));
            assertFalse(c.eligibleForPrivatePaperResearch(p, AVAILABLE, SESSION));
            assertFalse(p.eligibleForTraining(AVAILABLE, SESSION));
        }
    }

    @Test void eachConsumerEconomicsMismatchRejectsWithoutCoercion() {
        var p = CanonicalFoObservedMetadataGateV1.map(observed(
                CanonicalFoContractV1.OptionSide.CE, true, true, true));
        assertTrue(consumer(p).eligibleForPrivatePaperResearch(p, AVAILABLE, SESSION));
        assertFalse(new CanonicalFoIdentityHandoffV1("NSE_FO|OTHER", p.underlyingListingId(),
                p.exchange(), p.expiry(), p.strike(), p.side(), p.lotSize(), p.tickSize(),
                p.availableAt()).matches(p));
        assertFalse(new CanonicalFoIdentityHandoffV1(p.instrumentKey(), p.underlyingListingId(),
                p.exchange(), p.expiry().plusDays(7), p.strike(), p.side(), p.lotSize(),
                p.tickSize(), p.availableAt()).matches(p));
        assertFalse(new CanonicalFoIdentityHandoffV1(p.instrumentKey(), p.underlyingListingId(),
                p.exchange(), p.expiry(), p.strike(), p.side(), p.lotSize() + 1,
                p.tickSize(), p.availableAt()).matches(p));
        assertFalse(new CanonicalFoIdentityHandoffV1(p.instrumentKey(), p.underlyingListingId(),
                p.exchange(), p.expiry(), p.strike(), p.side(), p.lotSize(),
                new BigDecimal("0.10"), p.availableAt()).matches(p));
        assertFalse(new CanonicalFoIdentityHandoffV1(p.instrumentKey(), p.underlyingListingId(),
                p.exchange(), p.expiry(), p.strike(), CanonicalFoContractV1.OptionSide.PE,
                p.lotSize(), p.tickSize(), p.availableAt()).matches(p));
        assertFalse(new CanonicalFoIdentityHandoffV1(p.instrumentKey(), p.underlyingListingId(),
                p.exchange(), p.expiry(), p.strike(), p.side(), p.lotSize(),
                p.tickSize(), p.availableAt().plusSeconds(1)).matches(p));
    }

    @Test void noSourceUseGrantFromMappingOrAuthentication() {
        for (int missing = 0; missing < 3; missing++) {
            var o = observed(CanonicalFoContractV1.OptionSide.CE,
                    missing != 0, missing != 1, missing != 2);
            var p = CanonicalFoObservedMetadataGateV1.map(o);
            assertTrue(consumer(p).matches(p));
            assertFalse(consumer(p).eligibleForPrivatePaperResearch(p, AVAILABLE, SESSION));
            assertFalse(p.eligibleForTraining(AVAILABLE, SESSION));
        }
        var grantedForSyntheticResearch = CanonicalFoObservedMetadataGateV1.map(
                observed(CanonicalFoContractV1.OptionSide.PE, true, true, true));
        assertFalse(grantedForSyntheticResearch.eligibleForTraining(AVAILABLE, SESSION));
        assertFalse(consumer(grantedForSyntheticResearch).eligibleForPrivatePaperResearch(
                grantedForSyntheticResearch, ACQUIRED, SESSION));
        assertFalse(consumer(grantedForSyntheticResearch).eligibleForPrivatePaperResearch(
                grantedForSyntheticResearch, AVAILABLE, EXPIRY.plusDays(1)));
    }
}
