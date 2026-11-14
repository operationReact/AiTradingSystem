package in.aitradingsystem.canonical.fo;

import static org.junit.jupiter.api.Assertions.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;

class CanonicalFoIdentityHandoffV1Test {
    private static final Instant ACQUIRED = Instant.parse("2026-11-14T09:00:00Z");
    private static final Instant AVAILABLE = Instant.parse("2026-11-14T09:00:01Z");
    private static final LocalDate EXPIRY = LocalDate.parse("2026-11-17");
    private static final LocalDate SESSION = LocalDate.parse("2026-11-16");

    private CanonicalFoContractV1 producer(boolean listed, boolean read, boolean display) {
        // Synthetic shape only; NOT a real exchange contract, quote, or rights receipt.
        return new CanonicalFoContractV1("NSE_FO|SYNTHETIC_OPTION_1",
                "NSE_INDEX|SYNTHETIC_NIFTY", "NSE_FO", EXPIRY,
                new BigDecimal("25000.00"), CanonicalFoContractV1.OptionSide.CE,
                65, new BigDecimal("0.050"), "SYNTHETIC_FIXTURE",
                null, ACQUIRED, AVAILABLE, listed, read, display, false, false);
    }

    private CanonicalFoIdentityHandoffV1 handoff() {
        return new CanonicalFoIdentityHandoffV1("NSE_FO|SYNTHETIC_OPTION_1",
                "NSE_INDEX|SYNTHETIC_NIFTY", "NSE_FO", EXPIRY,
                new BigDecimal("25000"), CanonicalFoContractV1.OptionSide.CE,
                65, new BigDecimal("0.05"), AVAILABLE);
    }

    @Test void exactIdentityAndEconomicsMatchButUnknownRightsFailClosed() {
        var handoff = handoff();
        assertTrue(handoff.matches(producer(false, false, false)));
        assertFalse(handoff.eligibleForPrivatePaperResearch(
                producer(false, false, false), AVAILABLE, SESSION));
        assertFalse(handoff.eligibleForPrivatePaperResearch(
                producer(true, false, true), AVAILABLE, SESSION));
        assertFalse(handoff.eligibleForPrivatePaperResearch(
                producer(true, true, false), AVAILABLE, SESSION));
        assertTrue(handoff.eligibleForPrivatePaperResearch(
                producer(true, true, true), AVAILABLE, SESSION));
        assertFalse(handoff.eligibleForPrivatePaperResearch(
                producer(true, true, true), ACQUIRED, SESSION));
        assertFalse(handoff.eligibleForPrivatePaperResearch(
                producer(true, true, true), AVAILABLE, EXPIRY.plusDays(1)));
        assertFalse(producer(true, true, true).eligibleForTraining(AVAILABLE, SESSION));
    }

    @Test void mismatchedIdentityLotTickExpiryAndSideReject() {
        var p = producer(true, true, true);
        assertFalse(new CanonicalFoIdentityHandoffV1("NSE_FO|OTHER", p.underlyingListingId(),
                p.exchange(), p.expiry(), p.strike(), p.side(), p.lotSize(),
                p.tickSize(), p.availableAt()).matches(p));
        assertFalse(new CanonicalFoIdentityHandoffV1(p.instrumentKey(), "OTHER",
                p.exchange(), p.expiry(), p.strike(), p.side(), p.lotSize(),
                p.tickSize(), p.availableAt()).matches(p));
        assertFalse(new CanonicalFoIdentityHandoffV1(p.instrumentKey(), p.underlyingListingId(),
                p.exchange(), p.expiry().plusDays(7), p.strike(), p.side(), p.lotSize(),
                p.tickSize(), p.availableAt()).matches(p));
        assertFalse(new CanonicalFoIdentityHandoffV1(p.instrumentKey(), p.underlyingListingId(),
                p.exchange(), p.expiry(), p.strike(), CanonicalFoContractV1.OptionSide.PE,
                p.lotSize(), p.tickSize(), p.availableAt()).matches(p));
        assertFalse(new CanonicalFoIdentityHandoffV1(p.instrumentKey(), p.underlyingListingId(),
                p.exchange(), p.expiry(), p.strike(), p.side(), p.lotSize() + 1,
                p.tickSize(), p.availableAt()).matches(p));
        assertFalse(new CanonicalFoIdentityHandoffV1(p.instrumentKey(), p.underlyingListingId(),
                p.exchange(), p.expiry(), p.strike(), p.side(), p.lotSize(),
                new BigDecimal("0.10"), p.availableAt()).matches(p));
        assertFalse(new CanonicalFoIdentityHandoffV1(p.instrumentKey(), p.underlyingListingId(),
                p.exchange(), p.expiry(), p.strike(), p.side(), p.lotSize(),
                p.tickSize(), p.availableAt().plusSeconds(1)).matches(p));
    }

    @Test void invalidMetadataCannotBePublished() {
        var h = handoff();
        assertThrows(IllegalArgumentException.class, () -> new CanonicalFoIdentityHandoffV1(
                "NSE_EQ|WRONG", h.underlyingListingId(), h.exchange(), h.expiry(),
                h.strike(), h.side(), h.lotSize(), h.tickSize(), h.availableAt()));
        assertThrows(IllegalArgumentException.class, () -> new CanonicalFoIdentityHandoffV1(
                h.instrumentKey(), h.underlyingListingId(), h.exchange(), h.expiry(),
                h.strike(), h.side(), 0, h.tickSize(), h.availableAt()));
        assertThrows(NullPointerException.class, () -> new CanonicalFoIdentityHandoffV1(
                h.instrumentKey(), h.underlyingListingId(), h.exchange(), h.expiry(),
                h.strike(), h.side(), h.lotSize(), h.tickSize(), null));
    }
}