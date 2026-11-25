package in.aitradingsystem.tradingrooms;

import static org.junit.jupiter.api.Assertions.*;

import in.aitradingsystem.tradingrooms.FnoFuturesBasisCarryV1.Assumptions;
import in.aitradingsystem.tradingrooms.FnoFuturesBasisCarryV1.Contract;
import in.aitradingsystem.tradingrooms.FnoFuturesBasisCarryV1.Decision;
import in.aitradingsystem.tradingrooms.FnoFuturesBasisCarryV1.Direction;
import in.aitradingsystem.tradingrooms.FnoFuturesBasisCarryV1.Quote;
import in.aitradingsystem.tradingrooms.FnoFuturesBasisCarryV1.SourceRights;
import in.aitradingsystem.tradingrooms.FnoFuturesBasisCarryV1.Status;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;

class FnoFuturesBasisCarryV1Test {
    private static final Instant NOW = Instant.parse("2026-11-20T05:00:00Z");
    private static final String CASH = "NSE_EQ|INE002A01018";
    private static final Contract CONTRACT = new Contract(CASH,
        "NSE_FO|987654", CASH, LocalDate.of(2026, 12, 24), 250, true, true);
    private static final SourceRights RIGHTS = new SourceRights(true, true, true, false);
    private static final Assumptions COSTS = new Assumptions(
        dec("600"), dec("0"), dec("100"), dec("20"), dec("25"));

    private static BigDecimal dec(String v) { return new BigDecimal(v); }
    private static Quote quote(String bid, String ask) {
        return new Quote(dec(bid), dec(ask), NOW.minusSeconds(2), NOW.minusSeconds(1));
    }
    private static Decision evaluate(Contract c, Quote spot, Quote futures,
                                     SourceRights rights, Assumptions costs) {
        return FnoFuturesBasisCarryV1.evaluate(c, spot, futures, rights, costs, NOW);
    }

    @Test
    void cashAndCarryAboveFundingAndAllInCostsCreatesOnlyResearchCandidate() {
        Decision result = evaluate(CONTRACT, quote("100.00", "100.10"),
            quote("103.50", "103.70"), RIGHTS, COSTS);
        assertEquals(Status.RESEARCH_CANDIDATE, result.status());
        assertEquals(Direction.CASH_AND_CARRY, result.direction());
        assertEquals("strategy.STR-170", result.strategyId());
        assertEquals("NET_CASH_CARRY_RESEARCH_EDGE", result.reasonCode());
        assertTrue(result.selectedNetBps().compareTo(dec("25")) > 0);
        assertTrue(result.illustrativeNetPerLotInr().signum() > 0);
        assertNull(result.reverseCarryNetBps());
        assertFalse(result.executable());
        assertFalse(result.orderPlaced());
    }

    @Test
    void reverseCarryRequiresIndependentOvernightStockBorrowProof() {
        Quote spot = quote("100.00", "100.10");
        Quote lowFuture = quote("95.90", "96.00");
        assertEquals(Status.NO_EDGE, evaluate(CONTRACT, spot, lowFuture,
            RIGHTS, COSTS).status());
        Decision allowedResearch = evaluate(CONTRACT, spot, lowFuture,
            new SourceRights(true, true, true, true), COSTS);
        assertEquals(Status.RESEARCH_CANDIDATE, allowedResearch.status());
        assertEquals(Direction.REVERSE_CARRY, allowedResearch.direction());
        assertFalse(allowedResearch.executable());
    }

    @Test
    void normalSpotFutureSpreadDoesNotInventEdge() {
        Decision d = evaluate(CONTRACT, quote("99.90", "100.10"),
            quote("100.40", "100.60"), RIGHTS, COSTS);
        assertEquals(Status.NO_EDGE, d.status());
        assertEquals(Direction.NONE, d.direction());
        assertNull(d.illustrativeNetPerLotInr());
    }

    @Test
    void rightsAreAlwaysEnforcedBeforeQuoteArithmetic() {
        Decision d = evaluate(CONTRACT, quote("100", "101"), quote("120", "121"),
            new SourceRights(true, false, true, false), COSTS);
        assertEquals(Status.BLOCKED_RIGHTS, d.status());
        assertEquals("FNO_SOURCE_USE_RIGHTS_NOT_VERIFIED", d.reasonCode());
        assertNull(d.selectedNetBps());
        assertFalse(d.executable());
    }

    @Test
    void indexSpotAndMismatchAndUnverifiedContractAreNotAccepted() {
        for (Contract invalid : new Contract[] {
            new Contract("NSE_INDEX|Nifty 50", "NSE_FO|987654",
                "NSE_INDEX|Nifty 50", CONTRACT.expiry(), 50, true, true),
            new Contract(CASH, "NSE_FO|987654", "NSE_EQ|DIFFERENT",
                CONTRACT.expiry(), 50, true, true),
            new Contract(CASH, "NSE_FO|987654", CASH,
                CONTRACT.expiry(), 250, false, true),
            new Contract(CASH, "NSE_FO|987654", CASH,
                CONTRACT.expiry(), 250, true, false),
            new Contract(CASH, "NSE_FO|987654", CASH,
                LocalDate.of(2026, 11, 21), 250, true, true)
        }) {
            assertEquals(Status.BLOCKED_DATA,
                evaluate(invalid, quote("100", "101"), quote("120", "121"),
                    RIGHTS, COSTS).status());
        }
    }

    @Test
    void staleFutureQuoteOrFutureKnownObservationCannotCreateSignal() {
        Quote spot = quote("100", "100.1");
        Quote stale = new Quote(dec("120"), dec("121"),
            NOW.minusSeconds(30), NOW.minusSeconds(29));
        Quote forwardKnown = new Quote(dec("120"), dec("121"),
            NOW.minusSeconds(1), NOW.plusSeconds(1));
        for (Quote invalid : new Quote[] {stale, forwardKnown}) {
            assertEquals("FNO_QUOTE_INVALID_OR_STALE",
                evaluate(CONTRACT, spot, invalid, RIGHTS, COSTS).reasonCode());
        }
    }

    @Test
    void marketSessionAndCrossInstrumentExchangeTimeSkewBlock() {
        assertEquals("EXCHANGE_SESSION_NOT_VERIFIED", evaluate(CONTRACT,
            quote("100", "101"), quote("120", "121"),
            new SourceRights(true, true, false, false), COSTS).reasonCode());
        Quote skewed = new Quote(dec("120"), dec("121"),
            NOW.minusSeconds(14), NOW.minusSeconds(12));
        assertEquals("SPOT_FUTURES_EXCHANGE_TIME_SKEW", evaluate(CONTRACT,
            quote("100", "101"), skewed, RIGHTS, COSTS).reasonCode());
    }

    @Test
    void missingCostsOrNegativeRatesBlockInsteadOfAssumingFreeTrades() {
        assertEquals("FUTURES_CARRY_COST_ASSUMPTIONS_INVALID",
            evaluate(CONTRACT, quote("100", "101"), quote("120", "121"),
                RIGHTS, new Assumptions(dec("600"), dec("0"), dec("0"),
                    dec("0"), dec("25"))).reasonCode());
        assertEquals(Status.BLOCKED_DATA,
            evaluate(CONTRACT, quote("100", "101"), quote("120", "121"),
                RIGHTS, null).status());
    }

    @Test
    void invertedQuotesAreRejectedAndCalculationIsDeterministic() {
        assertEquals(Status.BLOCKED_DATA, evaluate(CONTRACT,
            quote("102", "101"), quote("120", "121"), RIGHTS, COSTS).status());
        Decision first = evaluate(CONTRACT, quote("100", "100.1"),
            quote("103.5", "103.7"), RIGHTS, COSTS);
        Decision second = evaluate(CONTRACT, quote("100", "100.1"),
            quote("103.5", "103.7"), RIGHTS, COSTS);
        assertEquals(first, second);
    }
}
