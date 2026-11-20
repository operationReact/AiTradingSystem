package in.aitradingsystem.tradingrooms;

import static org.junit.jupiter.api.Assertions.*;

import in.aitradingsystem.tradingrooms.FnoCashFuturesArbitrageResearchV1.AdditionalCosts;
import in.aitradingsystem.tradingrooms.FnoCashFuturesArbitrageResearchV1.Capital;
import in.aitradingsystem.tradingrooms.FnoCashFuturesArbitrageResearchV1.Decision;
import in.aitradingsystem.tradingrooms.FnoCashFuturesArbitrageResearchV1.Leg;
import in.aitradingsystem.tradingrooms.FnoCashFuturesArbitrageResearchV1.Status;
import in.aitradingsystem.tradingrooms.FnoFuturesBasisCarryV1.Assumptions;
import in.aitradingsystem.tradingrooms.FnoFuturesBasisCarryV1.Contract;
import in.aitradingsystem.tradingrooms.FnoFuturesBasisCarryV1.Quote;
import in.aitradingsystem.tradingrooms.FnoFuturesBasisCarryV1.SourceRights;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;

class FnoCashFuturesArbitrageResearchV1Test {
    private static final Instant AS_OF = Instant.parse("2026-11-20T05:00:00Z");
    private static final String SPOT = "NSE_EQ|INE002A01018";
    private static final String FUT = "NSE_FO|987654";
    private static final Contract CONTRACT = new Contract(SPOT, FUT, SPOT,
        LocalDate.of(2026, 12, 24), 250, true, true);
    private static final SourceRights RIGHTS = new SourceRights(true, true, true, false);
    private static final Assumptions BASIS = new Assumptions(
        d("600"), d("0"), d("100"), d("20"), d("25"));
    private static final Capital CAPITAL = new Capital(
        d("50000"), d("8000"), d("3000"), d("2000"), true, true, true);
    private static final AdditionalCosts COSTS = new AdditionalCosts(
        d("0.03"), d("50"), d("20"), d("15"), d("250"), true, true, true, true);

    private static BigDecimal d(String x) { return new BigDecimal(x); }
    private static Quote q(String bid, String ask) {
        return new Quote(d(bid), d(ask), AS_OF.minusSeconds(2), AS_OF.minusSeconds(1));
    }
    private static Leg spot() { return new Leg(SPOT, "UPSTOX_V3", q("100", "100.10"), 600, true); }
    private static Leg fut() { return new Leg(FUT, "UPSTOX_V3", q("103.50", "103.70"), 500, true); }
    private static Decision evaluate(Contract c, Leg spot, Leg fut, SourceRights r,
                                     Assumptions b, Capital capital, AdditionalCosts costs) {
        return FnoCashFuturesArbitrageResearchV1.evaluate(
            c, spot, fut, r, b, capital, costs, AS_OF);
    }
    private static Decision evaluate() {
        return evaluate(CONTRACT, spot(), fut(), RIGHTS, BASIS, CAPITAL, COSTS);
    }

    @Test
    void favorableCarryAndFullyFundedDeliveryProduceResearchOnlyFeasibility() {
        Decision x = evaluate();
        assertEquals(Status.RESEARCH_CANDIDATE, x.status());
        assertEquals("STRESSED_CASH_FUT_RESEARCH_CANDIDATE", x.reasonCode());
        assertEquals("strategy.STR-175", x.strategyId());
        assertEquals(SPOT, x.spotKey());
        assertEquals(FUT, x.futureKey());
        assertEquals("UPSTOX_V3", x.source());
        assertEquals(0, d("38025.00").compareTo(x.requiredCapitalInr()));
        assertTrue(x.basisNetPerLotInr().signum() > 0);
        assertTrue(x.incrementalDeductionsPerLotInr().signum() > 0);
        assertTrue(x.stressedNetPerLotInr().compareTo(d("250")) > 0);
        assertTrue(x.returnOnRequiredCapitalBps().signum() > 0);
        assertFalse(x.executable());
        assertFalse(x.orderPlaced());
        assertFalse(x.riskFreeArbitrageVerified());
    }

    @Test
    void additionalFeesAndUncertaintyCanEliminateApparentBasisProfit() {
        AdditionalCosts stressed = new AdditionalCosts(
            d("0.20"), d("200"), d("200"), d("150"), d("250"), true, true, true, true);
        Decision x = evaluate(CONTRACT, spot(), fut(), RIGHTS, BASIS, CAPITAL, stressed);
        assertEquals(Status.NO_EDGE, x.status());
        assertEquals("CASH_FUT_STRESSED_EDGE_BELOW_HURDLE", x.reasonCode());
        assertFalse(x.executable());
    }

    @Test
    void insufficientDepthOnEitherLegPreventsFeasibility() {
        for (Leg smallSpot : new Leg[] {
            new Leg(SPOT, "UPSTOX_V3", spot().quote(), 249, true)
        }) {
            assertEquals("CASH_FUT_TWO_LEG_DEPTH_INSUFFICIENT", evaluate(CONTRACT,
                smallSpot, fut(), RIGHTS, BASIS, CAPITAL, COSTS).reasonCode());
        }
        Leg shallowFuture = new Leg(FUT, "UPSTOX_V3", fut().quote(), 150, true);
        assertEquals(Status.INFEASIBLE, evaluate(CONTRACT, spot(),
            shallowFuture, RIGHTS, BASIS, CAPITAL, COSTS).status());
    }

    @Test
    void insufficientAvailableCashIncludingStressMarginStopsCandidate() {
        Capital insufficient = new Capital(d("33000"), d("8000"), d("3000"),
            d("2000"), true, true, true);
        Decision x = evaluate(CONTRACT, spot(), fut(), RIGHTS, BASIS, insufficient, COSTS);
        assertEquals(Status.INFEASIBLE, x.status());
        assertEquals("CASH_FUT_AVAILABLE_CAPITAL_INSUFFICIENT", x.reasonCode());
        assertEquals(0, d("38025.00").compareTo(x.requiredCapitalInr()));
        assertNull(x.stressedNetPerLotInr());
    }

    @Test
    void missingBrokerMarginSettlementOrPhysicalDeliveryProofFailsClosed() {
        for (Capital c : new Capital[] {
            new Capital(d("50000"), d("8000"), d("3000"), d("2000"), false, true, true),
            new Capital(d("50000"), d("8000"), d("3000"), d("2000"), true, false, true),
            new Capital(d("50000"), d("8000"), d("3000"), d("2000"), true, true, false),
            new Capital(d("50000"), d("0"), d("3000"), d("2000"), true, true, true)
        }) {
            assertEquals("CASH_FUT_CAPITAL_OR_COST_PROOF_MISSING",
                evaluate(CONTRACT, spot(), fut(), RIGHTS, BASIS, c, COSTS).reasonCode());
        }
    }

    @Test
    void unverifiedStatutoryDividendsCorporateActionsOrFeeDedupFailsClosed() {
        for (AdditionalCosts c : new AdditionalCosts[] {
            new AdditionalCosts(d("0.03"), d("50"), d("20"), d("15"),
                d("250"), false, true, true, true),
            new AdditionalCosts(d("0.03"), d("50"), d("20"), d("15"),
                d("250"), true, false, true, true),
            new AdditionalCosts(d("0.03"), d("50"), d("20"), d("15"),
                d("250"), true, true, false, true),
            new AdditionalCosts(d("0.03"), d("50"), d("20"), d("15"),
                d("250"), true, true, true, false),
            new AdditionalCosts(d("-0.03"), d("50"), d("20"), d("15"),
                d("250"), true, true, true, true)
        }) {
            assertEquals(Status.BLOCKED_DATA,
                evaluate(CONTRACT, spot(), fut(), RIGHTS, BASIS, CAPITAL, c).status());
        }
    }

    @Test
    void missingSpotOrFuturesMarketUseRightsCannotProduceSignal() {
        for (SourceRights r : new SourceRights[] {
            null, new SourceRights(false, true, true, false),
            new SourceRights(true, false, true, false)
        }) {
            Decision x = evaluate(CONTRACT, spot(), fut(), r, BASIS, CAPITAL, COSTS);
            assertEquals(Status.BLOCKED_RIGHTS, x.status());
            assertEquals("CASH_FUT_SOURCE_RIGHTS_UNVERIFIED", x.reasonCode());
        }
    }

    @Test
    void unknownBrokerSourceOrCrossProviderQuotesAreNotStitchedTogether() {
        Leg other = new Leg(FUT, "ANGEL_ONE_SMARTAPI", fut().quote(), 500, true);
        Leg unknown = new Leg(FUT, "MOCK", fut().quote(), 500, true);
        assertEquals("CASH_FUT_MATCHED_SOURCE_OR_DEPTH_INVALID",
            evaluate(CONTRACT, spot(), other, RIGHTS, BASIS, CAPITAL, COSTS).reasonCode());
        assertEquals(Status.BLOCKED_DATA,
            evaluate(CONTRACT, spot(), unknown, RIGHTS, BASIS, CAPITAL, COSTS).status());
    }

    @Test
    void mismatchedInstrumentIdentityOrUnverifiedProvenanceIsBlocked() {
        Leg wrong = new Leg("NSE_FO|WRONG", "UPSTOX_V3", fut().quote(), 500, true);
        Leg unverified = new Leg(SPOT, "UPSTOX_V3", spot().quote(), 600, false);
        assertEquals(Status.BLOCKED_DATA,
            evaluate(CONTRACT, spot(), wrong, RIGHTS, BASIS, CAPITAL, COSTS).status());
        assertEquals(Status.BLOCKED_DATA,
            evaluate(CONTRACT, unverified, fut(), RIGHTS, BASIS, CAPITAL, COSTS).status());
    }

    @Test
    void oldOrFutureObservedQuotesCannotBeReusedForNewEvaluation() {
        Leg stale = new Leg(FUT, "UPSTOX_V3", new Quote(
            d("103.5"), d("103.7"), AS_OF.minusSeconds(30), AS_OF.minusSeconds(29)),
            500, true);
        Leg futureKnown = new Leg(FUT, "UPSTOX_V3", new Quote(
            d("103.5"), d("103.7"), AS_OF.minusSeconds(1), AS_OF.plusSeconds(1)),
            500, true);
        assertEquals(Status.BLOCKED_DATA,
            evaluate(CONTRACT, spot(), stale, RIGHTS, BASIS, CAPITAL, COSTS).status());
        assertEquals(Status.BLOCKED_DATA,
            evaluate(CONTRACT, spot(), futureKnown, RIGHTS, BASIS, CAPITAL, COSTS).status());
    }

    @Test
    void impossibleSpotFutureTimestampSkewOrExpiredContractFailsBaseGate() {
        Leg skew = new Leg(FUT, "UPSTOX_V3", new Quote(
            d("103.5"), d("103.7"), AS_OF.minusSeconds(14), AS_OF.minusSeconds(12)),
            500, true);
        assertEquals("CASH_FUT_BASIS_DATA_INVALID",
            evaluate(CONTRACT, spot(), skew, RIGHTS, BASIS, CAPITAL, COSTS).reasonCode());
        Contract expired = new Contract(SPOT, FUT, SPOT,
            LocalDate.of(2026, 11, 19), 250, true, true);
        assertEquals(Status.BLOCKED_DATA,
            evaluate(expired, spot(), fut(), RIGHTS, BASIS, CAPITAL, COSTS).status());
    }

    @Test
    void ordinaryNoEdgeCannotBePromotedByLargeCapitalBalance() {
        Leg normal = new Leg(FUT, "UPSTOX_V3",
            q("100.50", "100.70"), 500, true);
        assertEquals("CASH_FUT_BASIS_EDGE_NOT_PRESENT",
            evaluate(CONTRACT, spot(), normal, RIGHTS, BASIS, CAPITAL, COSTS).reasonCode());
    }

    @Test
    void reverseCarryIsExplicitlyOutOfScopeEvenWithBorrowFlag() {
        SourceRights borrowed = new SourceRights(true, true, true, true);
        Leg cheaper = new Leg(FUT, "UPSTOX_V3", q("95.90", "96.00"), 500, true);
        Decision x = evaluate(CONTRACT, spot(), cheaper, borrowed, BASIS, CAPITAL, COSTS);
        assertEquals(Status.INFEASIBLE, x.status());
        assertEquals("CASH_FUT_REVERSE_BORROW_NOT_MODELED", x.reasonCode());
        assertFalse(x.executable());
    }

    @Test
    void unverifiedSessionOrMissingBasisFeesFailsClosedThroughStr170() {
        assertEquals(Status.BLOCKED_DATA,
            evaluate(CONTRACT, spot(), fut(),
                new SourceRights(true, true, false, false),
                BASIS, CAPITAL, COSTS).status());
        Assumptions free = new Assumptions(
            d("600"), d("0"), d("100"), d("0"), d("25"));
        assertEquals("CASH_FUT_BASIS_DATA_INVALID",
            evaluate(CONTRACT, spot(), fut(), RIGHTS, free, CAPITAL, COSTS).reasonCode());
    }

    @Test
    void exactSameInputsAlwaysProduceSameResultAndNoOrders() {
        assertEquals(evaluate(), evaluate());
        assertFalse(evaluate().riskFreeArbitrageVerified());
    }
}
