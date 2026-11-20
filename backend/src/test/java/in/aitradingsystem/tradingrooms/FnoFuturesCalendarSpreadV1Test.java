package in.aitradingsystem.tradingrooms;

import static org.junit.jupiter.api.Assertions.*;

import in.aitradingsystem.tradingrooms.FnoFuturesCalendarSpreadV1.Assumptions;
import in.aitradingsystem.tradingrooms.FnoFuturesCalendarSpreadV1.Contract;
import in.aitradingsystem.tradingrooms.FnoFuturesCalendarSpreadV1.Decision;
import in.aitradingsystem.tradingrooms.FnoFuturesCalendarSpreadV1.Direction;
import in.aitradingsystem.tradingrooms.FnoFuturesCalendarSpreadV1.Quote;
import in.aitradingsystem.tradingrooms.FnoFuturesCalendarSpreadV1.SourceRights;
import in.aitradingsystem.tradingrooms.FnoFuturesCalendarSpreadV1.Status;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;

class FnoFuturesCalendarSpreadV1Test {
    private static final Instant NOW = Instant.parse("2026-11-20T05:00:00Z");
    private static final String INDEX = "NSE_INDEX|Nifty 50";
    private static final Contract NEAR = new Contract(
        "NSE_FO|NEAR-01", INDEX, LocalDate.of(2026, 12, 24), 50, true);
    private static final Contract FAR = new Contract(
        "NSE_FO|FAR-02", INDEX, LocalDate.of(2027, 1, 28), 50, true);
    private static final SourceRights RIGHTS = new SourceRights(true, true, true, true);
    private static final Assumptions COSTS = new Assumptions(
        dec("600"), dec("0"), dec("15"), dec("25"));

    private static BigDecimal dec(String n) { return new BigDecimal(n); }
    private static Quote near(String bid, String ask) {
        return new Quote(NEAR.instrumentKey(), "UPSTOX_V3", dec(bid), dec(ask),
            NOW.minusSeconds(2), NOW.minusSeconds(1));
    }
    private static Quote far(String bid, String ask) {
        return new Quote(FAR.instrumentKey(), "UPSTOX_V3", dec(bid), dec(ask),
            NOW.minusSeconds(3), NOW.minusSeconds(1));
    }
    private static Decision evaluate(Contract near, Contract far, Quote n, Quote f,
                                     SourceRights rights, Assumptions assumptions) {
        return FnoFuturesCalendarSpreadV1.evaluate(
            near, far, n, f, rights, assumptions, NOW);
    }

    @Test
    void expensiveFarFutureProducesLongNearShortFarResearchOnly() {
        Decision result = evaluate(NEAR, FAR, near("24990", "25010"),
            far("25400", "25420"), RIGHTS, COSTS);
        assertEquals(Status.RESEARCH_CANDIDATE, result.status());
        assertEquals(Direction.LONG_NEAR_SHORT_FAR, result.direction());
        assertEquals("FAR_FUTURE_RICH_RESEARCH_EDGE", result.reasonCode());
        assertEquals("strategy.STR-171", result.strategyId());
        assertEquals(35, result.calendarDays());
        assertEquals("UPSTOX_V3", result.source());
        assertTrue(result.selectedNetBps().compareTo(dec("25")) > 0);
        assertTrue(result.illustrativeNetPerMatchedLotInr().signum() > 0);
        assertFalse(result.executable());
        assertFalse(result.orderPlaced());
    }

    @Test
    void cheapFarFutureProducesShortNearLongFarResearchOnly() {
        Decision result = evaluate(NEAR, FAR, near("24990", "25010"),
            far("24400", "24420"), RIGHTS, COSTS);
        assertEquals(Status.RESEARCH_CANDIDATE, result.status());
        assertEquals(Direction.SHORT_NEAR_LONG_FAR, result.direction());
        assertEquals("FAR_FUTURE_CHEAP_RESEARCH_EDGE", result.reasonCode());
        assertFalse(result.executable());
    }

    @Test
    void ordinaryTimeSpreadBelowCostsProducesNoSignal() {
        Decision result = evaluate(NEAR, FAR, near("24990", "25010"),
            far("25145", "25160"), RIGHTS, COSTS);
        assertEquals(Status.NO_EDGE, result.status());
        assertEquals(Direction.NONE, result.direction());
        assertNull(result.selectedNetBps());
        assertNull(result.illustrativeNetPerMatchedLotInr());
    }

    @Test
    void rightsForEachLegAndDiscoveryMustBeVerified() {
        for (SourceRights rights : new SourceRights[] {
            new SourceRights(false, true, true, true),
            new SourceRights(true, false, true, true),
            new SourceRights(true, true, false, true)
        }) {
            Decision result = evaluate(NEAR, FAR, near("24990", "25010"),
                far("25400", "25420"), rights, COSTS);
            assertEquals(Status.BLOCKED_RIGHTS, result.status());
            assertEquals("CALENDAR_SOURCE_RIGHTS_NOT_VERIFIED", result.reasonCode());
            assertNull(result.selectedNetBps());
        }
    }

    @Test
    void closedSessionAndExpiryRiskBlockTheEvaluation() {
        assertEquals("FNO_EXCHANGE_SESSION_NOT_VERIFIED", evaluate(NEAR, FAR,
            near("24990", "25010"), far("25400", "25420"),
            new SourceRights(true, true, true, false), COSTS).reasonCode());
        Contract nearExpiry = new Contract(NEAR.instrumentKey(), INDEX,
            LocalDate.of(2026, 11, 21), 50, true);
        assertEquals(Status.BLOCKED_DATA, evaluate(nearExpiry, FAR,
            near("24990", "25010"), far("25400", "25420"), RIGHTS, COSTS).status());
    }

    @Test
    void underlyingLotKeyAndContractProvenanceMismatchesBlock() {
        for (Contract invalidFar : new Contract[] {
            new Contract(FAR.instrumentKey(), "NSE_INDEX|Bank Nifty", FAR.expiry(), 50, true),
            new Contract(FAR.instrumentKey(), INDEX, FAR.expiry(), 75, true),
            new Contract(NEAR.instrumentKey(), INDEX, FAR.expiry(), 50, true),
            new Contract(FAR.instrumentKey(), INDEX, NEAR.expiry(), 50, true),
            new Contract(FAR.instrumentKey(), INDEX, FAR.expiry(), 50, false),
            new Contract("NSE_EQ|NOT_A_FUTURE", INDEX, FAR.expiry(), 50, true)
        }) {
            assertEquals("CALENDAR_CONTRACT_PAIR_INVALID", evaluate(NEAR, invalidFar,
                near("24990", "25010"), far("25400", "25420"),
                RIGHTS, COSTS).reasonCode());
        }
    }

    @Test
    void canonicalLiveV3LabelIsAcceptedAsQuoteSourceForBothLegs() {
        Decision result = evaluate(NEAR, FAR, near("24990", "25010"),
            far("25400", "25420"), RIGHTS, COSTS);
        assertEquals(Status.RESEARCH_CANDIDATE, result.status());
        assertEquals(in.aitradingsystem.marketdata.FnoMarketDataProvider.UPSTOX_V3_LIVE.wireLabel(),
            result.source());
    }

    @Test
    void restAnalyticsLabelIsNotAcceptedAsLiveQuoteSource() {
        Quote analytics = new Quote(FAR.instrumentKey(), "UPSTOX_ANALYTICS",
            dec("25400"), dec("25420"), NOW.minusSeconds(3), NOW.minusSeconds(1));
        assertEquals("CALENDAR_QUOTE_INVALID_OR_STALE", evaluate(NEAR, FAR,
            near("24990", "25010"), analytics, RIGHTS, COSTS).reasonCode());
    }

    @Test
    void sourceMismatchCannotSilentlyMixTwoBrokerFeeds() {
        Quote otherBroker = new Quote(FAR.instrumentKey(), "ANGEL_ONE_SMARTAPI",
            dec("25400"), dec("25420"), NOW.minusSeconds(3), NOW.minusSeconds(1));
        assertEquals("CALENDAR_QUOTE_SOURCE_MISMATCH", evaluate(NEAR, FAR,
            near("24990", "25010"), otherBroker, RIGHTS, COSTS).reasonCode());
    }

    @Test
    void staleFutureKnownWrongInstrumentAndInvertedQuotesBlock() {
        Quote stale = new Quote(FAR.instrumentKey(), "UPSTOX_V3",
            dec("25400"), dec("25420"), NOW.minusSeconds(20), NOW.minusSeconds(19));
        Quote future = new Quote(FAR.instrumentKey(), "UPSTOX_V3",
            dec("25400"), dec("25420"), NOW.minusSeconds(1), NOW.plusSeconds(1));
        Quote mismatched = new Quote(NEAR.instrumentKey(), "UPSTOX_V3",
            dec("25400"), dec("25420"), NOW.minusSeconds(3), NOW.minusSeconds(1));
        Quote inverted = far("25420", "25400");
        for (Quote invalid : new Quote[] {stale, future, mismatched, inverted}) {
            assertEquals("CALENDAR_QUOTE_INVALID_OR_STALE", evaluate(
                NEAR, FAR, near("24990", "25010"), invalid, RIGHTS, COSTS).reasonCode());
        }
    }

    @Test
    void exchangeTimestampSkewBlocksEvenWithFreshQuotes() {
        Quote delayed = new Quote(FAR.instrumentKey(), "UPSTOX_V3",
            dec("25400"), dec("25420"), NOW.minusSeconds(14), NOW.minusSeconds(12));
        assertEquals("CALENDAR_QUOTE_TIME_SKEW", evaluate(NEAR, FAR,
            near("24990", "25010"), delayed, RIGHTS, COSTS).reasonCode());
    }

    @Test
    void unpricedCostsAndHighHurdleCannotCreateProfitableCandidate() {
        Assumptions noCosts = new Assumptions(dec("600"), dec("0"), dec("0"), dec("25"));
        assertEquals("CALENDAR_COST_ASSUMPTIONS_INVALID", evaluate(NEAR, FAR,
            near("24990", "25010"), far("25400", "25420"), RIGHTS, noCosts).reasonCode());
        assertEquals(Status.BLOCKED_DATA, evaluate(NEAR, FAR,
            near("24990", "25010"), far("25400", "25420"), RIGHTS, null).status());
        Assumptions severeHurdle = new Assumptions(dec("600"), dec("0"), dec("15"), dec("2000"));
        assertEquals(Status.NO_EDGE, evaluate(NEAR, FAR,
            near("24990", "25010"), far("25400", "25420"), RIGHTS, severeHurdle).status());
    }

    @Test
    void singleStockFuturesAlsoSupportedWhenPairReallyMatches() {
        Contract n = new Contract("NSE_FO|STOCK-NEAR", "NSE_EQ|INE002A01018",
            NEAR.expiry(), 250, true);
        Contract f = new Contract("NSE_FO|STOCK-FAR", "NSE_EQ|INE002A01018",
            FAR.expiry(), 250, true);
        Quote nq = new Quote(n.instrumentKey(), "UPSTOX_V3", dec("100"), dec("101"),
            NOW.minusSeconds(2), NOW.minusSeconds(1));
        Quote fq = new Quote(f.instrumentKey(), "UPSTOX_V3", dec("108"), dec("109"),
            NOW.minusSeconds(3), NOW.minusSeconds(1));
        Decision d = evaluate(n, f, nq, fq, RIGHTS, COSTS);
        assertEquals(Status.RESEARCH_CANDIDATE, d.status());
        assertFalse(d.executable());
    }

    @Test
    void sameAsOfSnapshotsAlwaysYieldSameDeterministicDecision() {
        Quote near = near("24990", "25010");
        Quote far = far("25400", "25420");
        assertEquals(evaluate(NEAR, FAR, near, far, RIGHTS, COSTS),
            evaluate(NEAR, FAR, near, far, RIGHTS, COSTS));
    }
}
