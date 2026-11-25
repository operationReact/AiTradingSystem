package in.aitradingsystem.canonical.fo;

import static org.junit.jupiter.api.Assertions.assertEquals;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;

class CanonicalFoQuoteEvidenceV1Test {
    private static final Instant T = Instant.parse("2026-11-16T04:00:00Z");
    private static final LocalDate SESSION = LocalDate.of(2026, 11, 16);
    private static final Duration AGE = Duration.ofSeconds(10);

    private CanonicalFoContractV1 contract(boolean rights, boolean listed) {
        return new CanonicalFoContractV1("NSE_FO|SYNTHETIC_CE", "NSE_INDEX|SYNTHETIC",
                "NSE_FO", LocalDate.of(2026, 11, 17), new BigDecimal("25000"),
                CanonicalFoContractV1.OptionSide.CE, 65, new BigDecimal("0.05"),
                "SYNTHETIC", T.minusSeconds(3), T.minusSeconds(2), T.minusSeconds(1),
                listed, rights, rights, false, false);
    }

    private CanonicalFoQuoteEvidenceV1.Evidence evidence(boolean rights, boolean listed,
            boolean tick, Instant exchangeAt) {
        return new CanonicalFoQuoteEvidenceV1.Evidence(contract(rights, listed),
                exchangeAt, T.minusSeconds(1), T, rights, tick);
    }

    @Test void aggregateChainReceiptNeverProvesPerContractTick() {
        assertEquals(CanonicalFoQuoteEvidenceV1.State.TICK_NOT_OBSERVED,
                CanonicalFoQuoteEvidenceV1.assess(evidence(true, true, false, null), T, SESSION, AGE));
    }

    @Test void unknownRightsFailClosedEvenWithTick() {
        assertEquals(CanonicalFoQuoteEvidenceV1.State.RIGHTS_DENIED,
                CanonicalFoQuoteEvidenceV1.assess(evidence(false, true, true, T.minusSeconds(2)), T, SESSION, AGE));
    }

    @Test void listingAndExpiryMustBeVerified() {
        assertEquals(CanonicalFoQuoteEvidenceV1.State.METADATA_UNVERIFIED,
                CanonicalFoQuoteEvidenceV1.assess(evidence(true, false, true, T.minusSeconds(2)), T, SESSION, AGE));
        assertEquals(CanonicalFoQuoteEvidenceV1.State.METADATA_UNVERIFIED,
                CanonicalFoQuoteEvidenceV1.assess(evidence(true, true, true, T.minusSeconds(2)),
                        T, LocalDate.of(2026, 11, 18), AGE));
    }

    @Test void freshnessIsExplicitAndNoFutureExchangeTimestampAccepted() {
        assertEquals(CanonicalFoQuoteEvidenceV1.State.STALE,
                CanonicalFoQuoteEvidenceV1.assess(evidence(true, true, true, T.minusSeconds(11)), T, SESSION, AGE));
        assertEquals(CanonicalFoQuoteEvidenceV1.State.UNKNOWN,
                CanonicalFoQuoteEvidenceV1.assess(evidence(true, true, true, T.plusSeconds(1)), T, SESSION, AGE));
        assertEquals(CanonicalFoQuoteEvidenceV1.State.QUOTE_OBSERVED,
                CanonicalFoQuoteEvidenceV1.assess(evidence(true, true, true, T.minusSeconds(2)), T, SESSION, AGE));
    }

    @Test void missingEvidenceRemainsUnknown() {
        assertEquals(CanonicalFoQuoteEvidenceV1.State.UNKNOWN,
                CanonicalFoQuoteEvidenceV1.assess(null, T, SESSION, AGE));
    }
}
