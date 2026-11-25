package in.aitradingsystem.tradingrooms;

import static org.junit.jupiter.api.Assertions.*;

import in.aitradingsystem.tradingrooms.FnoExpiryBasisReversionV1.Assumptions;
import in.aitradingsystem.tradingrooms.FnoExpiryBasisReversionV1.CompletedPair;
import in.aitradingsystem.tradingrooms.FnoExpiryBasisReversionV1.Contract;
import in.aitradingsystem.tradingrooms.FnoExpiryBasisReversionV1.Decision;
import in.aitradingsystem.tradingrooms.FnoExpiryBasisReversionV1.Pattern;
import in.aitradingsystem.tradingrooms.FnoExpiryBasisReversionV1.Quote;
import in.aitradingsystem.tradingrooms.FnoExpiryBasisReversionV1.SourceRights;
import in.aitradingsystem.tradingrooms.FnoExpiryBasisReversionV1.Status;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class FnoExpiryBasisReversionV1Test {
    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    private static final Instant NOW = Instant.parse("2026-11-20T11:00:00Z");
    private static final String SPOT = "NSE_EQ|INE002A01018";
    private static final String FUTURE = "NSE_FO|DEC_180";
    private static final LocalDate[] DATES = {
        LocalDate.of(2026, 11, 13), LocalDate.of(2026, 11, 16),
        LocalDate.of(2026, 11, 17), LocalDate.of(2026, 11, 18),
        LocalDate.of(2026, 11, 19)
    };
    private static final Contract CONTRACT = new Contract(
        SPOT, FUTURE, SPOT, LocalDate.of(2026, 11, 1),
        LocalDate.of(2026, 11, 27), 250, true, true);
    private static final SourceRights RIGHTS = new SourceRights(true, true, true);
    private static final Assumptions ASSUMPTIONS = new Assumptions(
        n("500"), n("0"), n("20"), 3, 150, 30, 50, 2500,
        true, true, true, true);

    private static BigDecimal n(String x) { return new BigDecimal(x); }
    private static Instant close(LocalDate day) {
        return day.atTime(LocalTime.of(15, 30)).atZone(IST).toInstant();
    }

    private static Quote quote(LocalDate day, String mid, int secondsFromClose) {
        BigDecimal v = n(mid);
        return new Quote(v.subtract(n("0.01")), v.add(n("0.01")),
            close(day).plusSeconds(secondsFromClose),
            close(day).plusSeconds(30));
    }

    private static CompletedPair pair(int i, String futureMid) {
        LocalDate d = DATES[i];
        return new CompletedPair(d, true, SPOT, FUTURE, "UPSTOX_ANALYTICS",
            quote(d, "100", -45), quote(d, futureMid, -30),
            true, true, close(d).plusSeconds(60),
            FnoExpiryBasisReversionV1.futuresClose(d).plusSeconds(80));
    }

    private static List<CompletedPair> above() {
        return List.of(pair(0, "104"), pair(1, "103.2"),
            pair(2, "102.6"), pair(3, "101.8"), pair(4, "100.85"));
    }
    private static List<CompletedPair> below() {
        return List.of(pair(0, "96"), pair(1, "97"),
            pair(2, "98"), pair(3, "99"), pair(4, "99.15"));
    }

    private static List<CompletedPair> replace(List<CompletedPair> original,
                                                int index, CompletedPair substitute) {
        var copy = new ArrayList<>(original);
        copy.set(index, substitute);
        return copy;
    }

    private static Decision run(Contract c, List<CompletedPair> pairs,
                                SourceRights r, Assumptions a, Instant asOf) {
        return FnoExpiryBasisReversionV1.evaluate(c, pairs, r, a, asOf);
    }
    private static Decision run(List<CompletedPair> pairs) {
        return run(CONTRACT, pairs, RIGHTS, ASSUMPTIONS, NOW);
    }

    private static CompletedPair replaceQuotes(CompletedPair pair, Quote spot, Quote future) {
        return new CompletedPair(pair.exchangeSession(), pair.sessionVerified(),
            pair.spotKey(), pair.futuresKey(), pair.provider(), spot, future,
            pair.spotFinalVerified(), pair.futureFinalVerified(),
            pair.spotFinalFirstKnownAt(), pair.futureFinalFirstKnownAt());
    }

    @Test
    void futuresFinalizationAt1535InvalidEvenWhenCashNearCloseQuotesMatch() {
        var bars = above();
        var latest = bars.get(4);
        var invalid = new CompletedPair(latest.exchangeSession(), true,
            latest.spotKey(), latest.futuresKey(), latest.provider(),
            latest.spot(), latest.future(), true, true,
            latest.spotFinalFirstKnownAt(),
            close(latest.exchangeSession()).plusSeconds(300));
        assertEquals("EXPIRY_BASIS_FINAL_PAIR_INVALID",
            run(replace(bars, 4, invalid)).reasonCode());
    }

    @Test
    void aboveFairClosingBasisConvergenceProducesOnlyResearchObservation() {
        Decision d = run(above());
        assertEquals(Status.RESEARCH_CANDIDATE, d.status());
        assertEquals(Pattern.ABOVE_FAIR_CONVERGING, d.pattern());
        assertEquals("EXPIRY_BASIS_ABOVE_FAIR_CONVERGENCE_OBSERVED", d.reasonCode());
        assertEquals("strategy.STR-180", d.strategyId());
        assertEquals(FUTURE, d.futuresKey());
        assertEquals("UPSTOX_ANALYTICS", d.provider());
        assertEquals(5, d.completedSessions());
        assertEquals(4, d.narrowingSteps());
        assertTrue(d.initialResidualBps().compareTo(n("300")) > 0);
        assertTrue(d.latestResidualBps().compareTo(n("50")) > 0);
        assertTrue(d.convergenceBps().compareTo(n("150")) > 0);
        assertTrue(d.afterCostResidualBps().compareTo(n("30")) > 0);
        assertFalse(d.executable());
        assertFalse(d.orderPlaced());
        assertFalse(d.liveTradeAuthorized());
    }

    @Test
    void belowFairClosingBasisConvergenceIsNotAnAutomaticShortOrLongTrade() {
        Decision d = run(below());
        assertEquals(Status.RESEARCH_CANDIDATE, d.status());
        assertEquals(Pattern.BELOW_FAIR_CONVERGING, d.pattern());
        assertEquals("EXPIRY_BASIS_BELOW_FAIR_CONVERGENCE_OBSERVED", d.reasonCode());
        assertTrue(d.initialResidualBps().signum() < 0);
        assertTrue(d.latestResidualBps().signum() < 0);
        assertFalse(d.orderPlaced());
    }

    @Test
    void insufficientStrictNarrowingDoesNotCountAsReversion() {
        var bars = List.of(pair(0, "104"), pair(1, "103"),
            pair(2, "103.4"), pair(3, "103.5"), pair(4, "101"));
        assertEquals("EXPIRY_BASIS_NARROWING_NOT_CONFIRMED", run(bars).reasonCode());
        assertEquals(Status.NO_SIGNAL, run(bars).status());
    }

    @Test
    void weakNetCompressionDoesNotQualifyEvenWhenEveryStepNarrows() {
        Assumptions strict = new Assumptions(n("500"), n("0"), n("20"),
            3, 1000, 30, 50, 2500, true, true, true, true);
        Decision d = run(CONTRACT, above(), RIGHTS, strict, NOW);
        assertEquals(Status.NO_SIGNAL, d.status());
        assertEquals("EXPIRY_BASIS_TOTAL_CONVERGENCE_TOO_SMALL", d.reasonCode());
    }

    @Test
    void alreadyConvergedBasisCannotBecomeResearchCandidateAfterCosts() {
        var bars = replace(above(), 4, pair(4, "100.20"));
        Decision d = run(bars);
        assertEquals(Status.NO_SIGNAL, d.status());
        assertEquals("EXPIRY_BASIS_COST_ADJUSTED_RESIDUAL_TOO_SMALL", d.reasonCode());
    }

    @Test
    void explicitlyHigherCostsCanConsumeRemainingResidual() {
        Assumptions expensive = new Assumptions(n("500"), n("0"), n("90"),
            3, 150, 30, 50, 2500, true, true, true, true);
        assertEquals("EXPIRY_BASIS_COST_ADJUSTED_RESIDUAL_TOO_SMALL",
            run(CONTRACT, above(), RIGHTS, expensive, NOW).reasonCode());
    }

    @Test
    void fairValueCrossingIsNotAStableSignedBasisReversion() {
        var crossed = replace(above(), 4, pair(4, "99.0"));
        Decision d = run(crossed);
        assertEquals(Status.NO_SIGNAL, d.status());
        assertEquals("EXPIRY_BASIS_RESIDUAL_CROSSED_FAIR", d.reasonCode());
    }

    @Test
    void spotFutureAndContractDiscoveryRightsAreIndependentlyRequired() {
        for (SourceRights r : new SourceRights[]{
            null, new SourceRights(false,true,true),
            new SourceRights(true,false,true), new SourceRights(true,true,false)}) {
            Decision d = run(CONTRACT, above(), r, ASSUMPTIONS, NOW);
            assertEquals(Status.BLOCKED_RIGHTS, d.status());
            assertEquals("EXPIRY_BASIS_SOURCE_RIGHTS_MISSING", d.reasonCode());
            assertNull(d.futuresKey());
        }
    }

    @Test
    void deliveryEligibilityUnderlyingIdentityAndContractLotMustBeVerified() {
        for (Contract c : new Contract[]{
            new Contract(SPOT, FUTURE, SPOT, CONTRACT.listedFrom(),
                CONTRACT.expiry(), 250, false, true),
            new Contract(SPOT, FUTURE, SPOT, CONTRACT.listedFrom(),
                CONTRACT.expiry(), 250, true, false),
            new Contract(SPOT, FUTURE, "NSE_EQ|ANOTHER", CONTRACT.listedFrom(),
                CONTRACT.expiry(), 250, true, true),
            new Contract("NSE_INDEX|Nifty 50", FUTURE, "NSE_INDEX|Nifty 50",
                CONTRACT.listedFrom(), CONTRACT.expiry(), 250, true, true),
            new Contract(SPOT, FUTURE, SPOT, CONTRACT.listedFrom(),
                CONTRACT.expiry(), 0, true, true)
        }) assertEquals(Status.BLOCKED_DATA,
            run(c, above(), RIGHTS, ASSUMPTIONS, NOW).status());
    }

    @Test
    void nearExpiryPhysicalDeliveryCutoffAndFarExpiryAreExcluded() {
        for (Contract c : new Contract[]{
            new Contract(SPOT, FUTURE, SPOT, CONTRACT.listedFrom(),
                LocalDate.of(2026, 11, 22), 250, true, true),
            new Contract(SPOT, FUTURE, SPOT, CONTRACT.listedFrom(),
                LocalDate.of(2026, 12, 20), 250, true, true),
            new Contract(SPOT, FUTURE, SPOT, LocalDate.of(2026, 12, 1),
                CONTRACT.expiry(), 250, true, true)
        }) assertEquals("EXPIRY_BASIS_CONTRACT_INVALID",
            run(c, above(), RIGHTS, ASSUMPTIONS, NOW).reasonCode());
    }

    @Test
    void mismatchedFutureExpiryOrSpotInstrumentBlocksHistoricalMixing() {
        var bars = above();
        CompletedPair p = bars.get(4);
        CompletedPair wrong = new CompletedPair(p.exchangeSession(), true,
            SPOT, "NSE_FO|NEXT_MONTH", p.provider(), p.spot(), p.future(), true, true,
            p.spotFinalFirstKnownAt(), p.futureFinalFirstKnownAt());
        assertEquals("EXPIRY_BASIS_FINAL_PAIR_INVALID",
            run(replace(bars, 4, wrong)).reasonCode());
        var wrongSpot = new CompletedPair(p.exchangeSession(), true,
            "NSE_EQ|ANOTHER", FUTURE, p.provider(), p.spot(), p.future(),
            true, true, p.spotFinalFirstKnownAt(), p.futureFinalFirstKnownAt());
        assertEquals(Status.BLOCKED_DATA, run(replace(bars, 4, wrongSpot)).status());
    }

    @Test
    void cannotStitchTwoAuthorizedProvidersIntoOneHistoricalPairSeries() {
        var bars = above();
        CompletedPair p = bars.get(4);
        CompletedPair changed = new CompletedPair(p.exchangeSession(), true,
            SPOT, FUTURE, "ANGEL_ONE_SMARTAPI", p.spot(), p.future(),
            true, true, p.spotFinalFirstKnownAt(), p.futureFinalFirstKnownAt());
        assertEquals("EXPIRY_BASIS_PROVIDER_CHANGED",
            run(replace(bars, 4, changed)).reasonCode());
    }

    @Test
    void finalCloseAndExchangeSessionMustBeIndependentlyVerified() {
        var bars = above();
        CompletedPair p = bars.get(4);
        for (CompletedPair bad : new CompletedPair[]{
            new CompletedPair(p.exchangeSession(), false, SPOT, FUTURE, p.provider(),
                p.spot(), p.future(), true, true,
                p.spotFinalFirstKnownAt(), p.futureFinalFirstKnownAt()),
            new CompletedPair(p.exchangeSession(), true, SPOT, FUTURE, p.provider(),
                p.spot(), p.future(), false, true,
                p.spotFinalFirstKnownAt(), p.futureFinalFirstKnownAt()),
            new CompletedPair(p.exchangeSession(), true, SPOT, FUTURE, p.provider(),
                p.spot(), p.future(), true, false,
                p.spotFinalFirstKnownAt(), p.futureFinalFirstKnownAt())
        }) assertEquals(Status.BLOCKED_DATA,
            run(replace(bars, 4, bad)).status());
    }

    @Test
    void originalPublicationAfterEvaluationInstantIsLookahead() {
        var bars = above();
        CompletedPair p = bars.get(4);
        CompletedPair futureKnown = new CompletedPair(p.exchangeSession(), true,
            SPOT, FUTURE, p.provider(), p.spot(), p.future(), true, true,
            p.spotFinalFirstKnownAt(), NOW.plusSeconds(1));
        assertEquals("EXPIRY_BASIS_FINAL_PAIR_INVALID",
            run(replace(bars, 4, futureKnown)).reasonCode());
    }

    @Test
    void closeFinalizationBeforeExchangeCloseAndRetroactivelyBackdatedDataBlock() {
        var bars = above();
        CompletedPair p = bars.get(4);
        CompletedPair preClose = new CompletedPair(p.exchangeSession(), true,
            SPOT, FUTURE, p.provider(), p.spot(), p.future(), true, true,
            close(p.exchangeSession()).minusSeconds(1), p.futureFinalFirstKnownAt());
        assertEquals(Status.BLOCKED_DATA,
            run(replace(bars, 4, preClose)).status());
        CompletedPair earlierThanObserved = new CompletedPair(
            p.exchangeSession(), true, SPOT, FUTURE, p.provider(),
            p.spot(), p.future(), true, true,
            p.spot().firstObservedAt().minusSeconds(1), p.futureFinalFirstKnownAt());
        assertEquals(Status.BLOCKED_DATA,
            run(replace(bars, 4, earlierThanObserved)).status());
    }

    @Test
    void staleFirstPublicationOrClosingQuoteOutsideTimeWindowRejected() {
        var bars = above();
        CompletedPair p = bars.get(4);
        CompletedPair delayedFinal = new CompletedPair(p.exchangeSession(), true,
            SPOT, FUTURE, p.provider(), p.spot(), p.future(), true, true,
            close(p.exchangeSession()).plusSeconds(37 * 3600L),
            p.futureFinalFirstKnownAt());
        assertEquals(Status.BLOCKED_DATA,
            run(replace(bars, 4, delayedFinal)).status());
        Quote intraday = new Quote(n("99.99"), n("100.01"),
            close(p.exchangeSession()).minusSeconds(600),
            p.spot().firstObservedAt());
        assertEquals(Status.BLOCKED_DATA,
            run(replace(bars, 4, replaceQuotes(p, intraday, p.future()))).status());
    }

    @Test
    void crossLegSkewAndCrossedBidAskCannotBeAccepted() {
        var bars = above();
        CompletedPair p = bars.get(4);
        Quote skew = new Quote(p.future().bid(), p.future().ask(),
            close(p.exchangeSession()).minusSeconds(200),
            p.future().firstObservedAt());
        assertEquals(Status.BLOCKED_DATA,
            run(replace(bars, 4, replaceQuotes(p, p.spot(), skew))).status());
        Quote inverted = new Quote(n("101"), n("100.9"),
            p.future().exchangeAt(), p.future().firstObservedAt());
        assertEquals(Status.BLOCKED_DATA,
            run(replace(bars, 4, replaceQuotes(p, p.spot(), inverted))).status());
    }

    @Test
    void widenedSpreadAndPriceSpikeCannotAppearAsCleanConvergence() {
        var bars = above();
        CompletedPair p = bars.get(4);
        Quote wide = new Quote(n("98"), n("102"),
            p.spot().exchangeAt(), p.spot().firstObservedAt());
        assertEquals("EXPIRY_BASIS_FINAL_PAIR_INVALID",
            run(replace(bars, 4, replaceQuotes(p, wide, p.future()))).reasonCode());
        CompletedPair spike = pair(4, "150");
        assertEquals("EXPIRY_BASIS_PRICE_OUTLIER",
            run(replace(bars, 4, spike)).reasonCode());
    }

    @Test
    void missingDuplicateAndOutOfOrderSessionsReject() {
        var bars = above();
        assertEquals(Status.BLOCKED_DATA,
            run(bars.subList(0, 4)).status());
        assertEquals("EXPIRY_BASIS_SESSION_SEQUENCE_INVALID",
            run(List.of(bars.get(0),bars.get(1),bars.get(2),
                bars.get(3),bars.get(3))).reasonCode());
        assertEquals(Status.BLOCKED_DATA,
            run(List.of(bars.get(0),bars.get(2),bars.get(1),
                bars.get(3),bars.get(4))).status());
    }

    @Test
    void originalLatestSessionMustNotBecomeStale() {
        // A later verified expiry keeps the contract in the valid expiry window
        // while testing the independent last-data freshness gate.
        Contract laterExpiry = new Contract(SPOT, FUTURE, SPOT,
            CONTRACT.listedFrom(), LocalDate.of(2026, 12, 4),
            CONTRACT.lotSize(), true, true);
        Decision d = run(laterExpiry, above(), RIGHTS, ASSUMPTIONS,
            NOW.plusSeconds(6 * 24 * 3600L));
        assertEquals("EXPIRY_BASIS_LAST_SESSION_STALE", d.reasonCode());
    }

    @Test
    void mandatoryDividendCorporateActionDeliveryAndCostVerificationCannotBeSkipped() {
        for (Assumptions a : new Assumptions[]{
            new Assumptions(n("500"),n("0"),n("20"),3,150,30,50,2500,
                false,true,true,true),
            new Assumptions(n("500"),n("0"),n("20"),3,150,30,50,2500,
                true,false,true,true),
            new Assumptions(n("500"),n("0"),n("20"),3,150,30,50,2500,
                true,true,false,true),
            new Assumptions(n("500"),n("0"),n("20"),3,150,30,50,2500,
                true,true,true,false)
        }) assertEquals("EXPIRY_BASIS_HISTORY_OR_COSTS_INVALID",
            run(CONTRACT, above(), RIGHTS, a, NOW).reasonCode());
    }

    @Test
    void invalidCostAndThresholdBoundsFailClosedRatherThanDefaulting() {
        assertEquals(Status.BLOCKED_DATA,
            run(CONTRACT,above(),RIGHTS,null,NOW).status());
        for (Assumptions a : new Assumptions[]{
            new Assumptions(n("-1"),n("0"),n("20"),3,150,30,50,2500,
                true,true,true,true),
            new Assumptions(n("500"),n("0"),n("0"),3,150,30,50,2500,
                true,true,true,true),
            new Assumptions(n("500"),n("0"),n("20"),2,150,30,50,2500,
                true,true,true,true),
            new Assumptions(n("500"),n("0"),n("20"),3,150,30,0,2500,
                true,true,true,true)
        }) assertEquals(Status.BLOCKED_DATA,
            run(CONTRACT,above(),RIGHTS,a,NOW).status());
    }

    @Test
    void identicalInputsHaveIdenticalNonExecutableResults() {
        assertEquals(run(above()),run(above()));
        assertFalse(run(below()).executable());
        assertFalse(run(below()).orderPlaced());
    }
}
