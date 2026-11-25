package in.aitradingsystem.tradingrooms;

import static org.junit.jupiter.api.Assertions.*;

import in.aitradingsystem.tradingrooms.FnoFuturesHedgeOverlayV1.Contract;
import in.aitradingsystem.tradingrooms.FnoFuturesHedgeOverlayV1.Decision;
import in.aitradingsystem.tradingrooms.FnoFuturesHedgeOverlayV1.Funding;
import in.aitradingsystem.tradingrooms.FnoFuturesHedgeOverlayV1.MarketQuote;
import in.aitradingsystem.tradingrooms.FnoFuturesHedgeOverlayV1.Portfolio;
import in.aitradingsystem.tradingrooms.FnoFuturesHedgeOverlayV1.ProposedSide;
import in.aitradingsystem.tradingrooms.FnoFuturesHedgeOverlayV1.SourceRights;
import in.aitradingsystem.tradingrooms.FnoFuturesHedgeOverlayV1.Status;
import in.aitradingsystem.tradingrooms.FnoFuturesHedgeOverlayV1.Stress;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;

class FnoFuturesHedgeOverlayV1Test {
    private static final Instant NOW = Instant.parse("2026-11-20T05:00:00Z");
    private static final String FUTURE = "NSE_FO|NIFTY_DEC_178";
    private static final String INDEX = "NSE_INDEX|Nifty 50";
    private static final Contract CONTRACT =
        new Contract(FUTURE, INDEX, LocalDate.of(2026, 12, 24), 25, true);
    private static final Portfolio PORTFOLIO =
        new Portfolio(INDEX, d("1000000"), NOW.minusSeconds(120),
            NOW.minusSeconds(60), true, true, true);
    private static final MarketQuote QUOTE = new MarketQuote(
        FUTURE, "UPSTOX_V3", d("20000"), d("20004"),
        200, 200, 1000000, NOW.minusSeconds(3),
        NOW.minusSeconds(1), true, true);
    private static final SourceRights RIGHTS = new SourceRights(true, true, true);
    private static final Funding FUNDS = new Funding(
        d("300000"), d("80000"), d("20000"), true, true);
    private static final Stress STRESS = new Stress(
        500, d("1000"), d("500"), 10, 50, 3000, true, true);

    private static BigDecimal d(String s) { return new BigDecimal(s); }

    private static Decision eval(Contract c, Portfolio p, MarketQuote q,
                                 SourceRights r, Funding f, Stress s) {
        return FnoFuturesHedgeOverlayV1.evaluate(c, p, q, r, f, s, NOW);
    }

    private static Decision eval() { return eval(CONTRACT, PORTFOLIO, QUOTE, RIGHTS, FUNDS, STRESS); }

    private static Portfolio exposure(String value) {
        return new Portfolio(INDEX, d(value), NOW.minusSeconds(120),
            NOW.minusSeconds(60), true, true, true);
    }

    private static MarketQuote quote(String bid, String ask, long bidDepth, long askDepth) {
        return new MarketQuote(FUTURE, "UPSTOX_V3", d(bid), d(ask),
            bidDepth, askDepth, 1000000,
            NOW.minusSeconds(3), NOW.minusSeconds(1), true, true);
    }

    @Test
    void positiveBetaExposureUsesTwoShortIndexFutureLotsToReduceModeledRisk() {
        Decision out = eval();
        assertEquals(Status.RESEARCH_CANDIDATE, out.status());
        assertEquals(ProposedSide.SHORT_INDEX_FUTURE, out.proposedSide());
        assertEquals("HEDGE_STRESSED_EXPOSURE_REDUCTION_RESEARCH_ONLY", out.reasonCode());
        assertEquals("strategy.STR-178", out.strategyId());
        assertEquals(FUTURE, out.instrumentKey());
        assertEquals("UPSTOX_V3", out.provider());
        assertEquals(2, out.hypotheticalLots());
        assertEquals(0, out.requiredFundingInr().compareTo(d("200000.00")));
        assertEquals(0, out.currentSignedBetaExposureInr().compareTo(d("1000000")));
        assertEquals(0, out.residualSignedBetaExposureInr().compareTo(d("0.00")));
        assertEquals(0, out.riskBeforeInr().compareTo(d("50000.00")));
        assertEquals(0, out.stressedRiskAfterInr().compareTo(d("3000.00")));
        assertEquals(0, out.riskReductionBps().compareTo(d("9400.0000")));
        assertFalse(out.executable());
        assertFalse(out.orderPlaced());
        assertFalse(out.liveTradeAuthorized());
    }

    @Test
    void negativePortfolioBetaSelectsLongSideWithoutOverhedging() {
        Decision out = eval(CONTRACT, exposure("-1000000"), QUOTE, RIGHTS, FUNDS, STRESS);
        assertEquals(Status.RESEARCH_CANDIDATE, out.status());
        assertEquals(ProposedSide.LONG_INDEX_FUTURE, out.proposedSide());
        assertEquals(1, out.hypotheticalLots());
        assertEquals(0, out.residualSignedBetaExposureInr().compareTo(d("-499900.00")));
        assertTrue(out.riskReductionBps().compareTo(d("3000")) > 0);
        assertFalse(out.executable());
    }

    @Test
    void alreadyBetaNeutralPortfolioNeverGeneratesATrade() {
        Decision out = eval(CONTRACT, exposure("0"), QUOTE, RIGHTS, FUNDS, STRESS);
        assertEquals(Status.NO_HEDGE, out.status());
        assertEquals("HEDGE_PORTFOLIO_ALREADY_INDEX_NEUTRAL", out.reasonCode());
        assertEquals(ProposedSide.NONE, out.proposedSide());
        assertEquals(0, out.hypotheticalLots());
    }

    @Test
    void oneLotLargerThanPortfolioExposureIsNeverSuggested() {
        Decision out = eval(CONTRACT, exposure("300000"), QUOTE, RIGHTS, FUNDS, STRESS);
        assertEquals(Status.NO_HEDGE, out.status());
        assertEquals("HEDGE_ONE_LOT_WOULD_OVERHEDGE", out.reasonCode());
    }

    @Test
    void constrainedMaxLotsRespectsRoomRiskPolicy() {
        Stress capped = new Stress(500, d("1000"), d("500"), 1, 50, 3000, true, true);
        Decision out = eval(CONTRACT, PORTFOLIO, QUOTE, RIGHTS, FUNDS, capped);
        assertEquals(Status.RESEARCH_CANDIDATE, out.status());
        assertEquals(1, out.hypotheticalLots());
        assertEquals(0, out.residualSignedBetaExposureInr().compareTo(d("500000.00")));
        assertEquals(0, out.requiredFundingInr().compareTo(d("100000.00")));
    }

    @Test
    void twoSideDepthRequirementUsesBidForShortsAndAskForLongs() {
        MarketQuote shallowBid = quote("20000", "20004", 24, 200);
        Decision shortSide = eval(CONTRACT, PORTFOLIO, shallowBid, RIGHTS, FUNDS, STRESS);
        assertEquals(Status.INFEASIBLE, shortSide.status());
        assertEquals("HEDGE_ONE_LOT_MARKET_DEPTH_INSUFFICIENT", shortSide.reasonCode());
        MarketQuote shallowAsk = quote("20000", "20004", 200, 24);
        Decision longSide = eval(CONTRACT, exposure("-1000000"),
            shallowAsk, RIGHTS, FUNDS, STRESS);
        assertEquals(Status.INFEASIBLE, longSide.status());
        assertEquals("HEDGE_ONE_LOT_MARKET_DEPTH_INSUFFICIENT", longSide.reasonCode());
    }

    @Test
    void depthCanLimitOptimalHedgeToOneLot() {
        MarketQuote oneLot = quote("20000", "20004", 25, 200);
        Decision out = eval(CONTRACT, PORTFOLIO, oneLot, RIGHTS, FUNDS, STRESS);
        assertEquals(Status.RESEARCH_CANDIDATE, out.status());
        assertEquals(1, out.hypotheticalLots());
    }

    @Test
    void insufficientAvailableMarginBlocksOneLot() {
        Funding less = new Funding(d("99999"), d("80000"), d("20000"), true, true);
        Decision out = eval(CONTRACT, PORTFOLIO, QUOTE, RIGHTS, less, STRESS);
        assertEquals(Status.INFEASIBLE, out.status());
        assertEquals("HEDGE_AVAILABLE_MARGIN_INSUFFICIENT", out.reasonCode());
        assertNull(out.stressedRiskAfterInr());
    }

    @Test
    void fundingOnlySupportsOneLotWithoutInventingBrokerCredit() {
        Funding less = new Funding(d("150000"), d("80000"), d("20000"), true, true);
        Decision out = eval(CONTRACT, PORTFOLIO, QUOTE, RIGHTS, less, STRESS);
        assertEquals(Status.RESEARCH_CANDIDATE, out.status());
        assertEquals(1, out.hypotheticalLots());
    }

    @Test
    void stressedBasisAndCostsCanMakeHedgeWorseThanNoHedge() {
        Stress expensive = new Stress(500, d("60000"), d("1000"), 10, 50, 3000, true, true);
        Decision out = eval(CONTRACT, PORTFOLIO, QUOTE, RIGHTS, FUNDS, expensive);
        assertEquals(Status.NO_HEDGE, out.status());
        assertEquals("HEDGE_STRESSED_RISK_NOT_IMPROVED", out.reasonCode());
    }

    @Test
    void stringentReductionHurdleRejectsEvenBeneficialHedge() {
        Stress strict = new Stress(500, d("1000"), d("500"), 10, 50, 9800, true, true);
        Decision out = eval(CONTRACT, PORTFOLIO, QUOTE, RIGHTS, FUNDS, strict);
        assertEquals(Status.NO_HEDGE, out.status());
        assertEquals("HEDGE_RISK_REDUCTION_BELOW_HURDLE", out.reasonCode());
        assertEquals(0, out.hypotheticalLots());
    }

    @Test
    void wideBidAskSpreadRejectsResearchCandidateBeforeSizing() {
        MarketQuote wide = quote("20000", "21000", 200, 200);
        Decision out = eval(CONTRACT, PORTFOLIO, wide, RIGHTS, FUNDS, STRESS);
        assertEquals(Status.NO_HEDGE, out.status());
        assertEquals("HEDGE_SPREAD_EXCEEDS_RESEARCH_LIMIT", out.reasonCode());
    }

    @Test
    void futuresPortfolioAndDiscoveryResearchRightsEachFailClosed() {
        for (SourceRights missing : new SourceRights[]{
            null, new SourceRights(false, true, true),
            new SourceRights(true, false, true),
            new SourceRights(true, true, false)}) {
            Decision out = eval(CONTRACT, PORTFOLIO, QUOTE, missing, FUNDS, STRESS);
            assertEquals(Status.BLOCKED_RIGHTS, out.status());
            assertEquals("HEDGE_RESEARCH_RIGHTS_NOT_VERIFIED", out.reasonCode());
            assertNull(out.instrumentKey());
        }
    }

    @Test
    void portfolioPositionAndExistingHedgeReconciliationIsMandatory() {
        for (Portfolio p : new Portfolio[]{
            new Portfolio(INDEX, d("1000000"), NOW.minusSeconds(120),
                NOW.minusSeconds(60), false, true, true),
            new Portfolio(INDEX, d("1000000"), NOW.minusSeconds(120),
                NOW.minusSeconds(60), true, false, true),
            new Portfolio(INDEX, d("1000000"), NOW.minusSeconds(120),
                NOW.minusSeconds(60), true, true, false),
            new Portfolio(INDEX, d("1000000"), NOW.minusSeconds(360),
                NOW.minusSeconds(350), true, true, true),
            new Portfolio(INDEX, d("1000000"), NOW.minusSeconds(120),
                NOW.plusSeconds(1), true, true, true)
        }) {
            assertEquals("HEDGE_CONTRACT_OR_PORTFOLIO_INVALID",
                eval(CONTRACT, p, QUOTE, RIGHTS, FUNDS, STRESS).reasonCode());
        }
    }

    @Test
    void hedgeMustMatchThePortfolioIndexAndNotStockFutures() {
        Portfolio bank = new Portfolio("NSE_INDEX|Nifty Bank", d("1000000"),
            NOW.minusSeconds(120), NOW.minusSeconds(60), true, true, true);
        assertEquals("HEDGE_PORTFOLIO_INDEX_MISMATCH",
            eval(CONTRACT, bank, QUOTE, RIGHTS, FUNDS, STRESS).reasonCode());
        Contract stock = new Contract(FUTURE, "NSE_EQ|INE002A01018",
            CONTRACT.expiry(), 25, true);
        assertEquals("HEDGE_CONTRACT_OR_PORTFOLIO_INVALID",
            eval(stock, PORTFOLIO, QUOTE, RIGHTS, FUNDS, STRESS).reasonCode());
    }

    @Test
    void unverifiedContractInvalidLotAndExpiringTodayAreBlocked() {
        for (Contract c : new Contract[]{
            new Contract(FUTURE, INDEX, CONTRACT.expiry(), 25, false),
            new Contract(FUTURE, INDEX, CONTRACT.expiry(), 0, true),
            new Contract(FUTURE, INDEX, LocalDate.of(2026, 11, 20), 25, true),
            new Contract("NSE_EQ|NOT_A_FUTURE", INDEX, CONTRACT.expiry(), 25, true)
        }) {
            assertEquals(Status.BLOCKED_DATA,
                eval(c, PORTFOLIO, QUOTE, RIGHTS, FUNDS, STRESS).status());
        }
    }

    @Test
    void wrongBrokerKeyClosedSessionAndUnverifiedQuoteProvenanceBlock() {
        MarketQuote badKey = new MarketQuote("NSE_FO|ANOTHER", "UPSTOX_V3",
            QUOTE.bid(), QUOTE.ask(), 200, 200, 1000000,
            QUOTE.exchangeAt(), QUOTE.observedAt(), true, true);
        MarketQuote wrongProvider = new MarketQuote(FUTURE, "SIMULATED",
            QUOTE.bid(), QUOTE.ask(), 200, 200, 1000000,
            QUOTE.exchangeAt(), QUOTE.observedAt(), true, true);
        MarketQuote closed = new MarketQuote(FUTURE, "UPSTOX_V3",
            QUOTE.bid(), QUOTE.ask(), 200, 200, 1000000,
            QUOTE.exchangeAt(), QUOTE.observedAt(), false, true);
        MarketQuote unverified = new MarketQuote(FUTURE, "UPSTOX_V3",
            QUOTE.bid(), QUOTE.ask(), 200, 200, 1000000,
            QUOTE.exchangeAt(), QUOTE.observedAt(), true, false);
        for (MarketQuote q : new MarketQuote[]{badKey, wrongProvider, closed, unverified}) {
            assertEquals("HEDGE_MARKET_QUOTE_INVALID_OR_STALE",
                eval(CONTRACT, PORTFOLIO, q, RIGHTS, FUNDS, STRESS).reasonCode());
        }
    }

    @Test
    void staleFutureKnownAndInvertedBidAskCannotBeHedgeQuotes() {
        MarketQuote stale = new MarketQuote(FUTURE, "UPSTOX_V3",
            d("20000"), d("20004"), 200, 200, 1000000,
            NOW.minusSeconds(22), NOW.minusSeconds(21), true, true);
        MarketQuote futureKnown = new MarketQuote(FUTURE, "UPSTOX_V3",
            d("20000"), d("20004"), 200, 200, 1000000,
            NOW.minusSeconds(2), NOW.plusSeconds(1), true, true);
        MarketQuote inverted = quote("20010", "20000", 200, 200);
        for (MarketQuote q : new MarketQuote[]{stale, futureKnown, inverted}) {
            assertEquals(Status.BLOCKED_DATA,
                eval(CONTRACT, PORTFOLIO, q, RIGHTS, FUNDS, STRESS).status());
        }
    }

    @Test
    void missingOpenInterestAndNegativeMarketDepthAreInvalid() {
        MarketQuote noOi = new MarketQuote(FUTURE, "UPSTOX_V3",
            QUOTE.bid(), QUOTE.ask(), 200, 200, 0,
            QUOTE.exchangeAt(), QUOTE.observedAt(), true, true);
        MarketQuote negativeDepth = quote("20000", "20004", -1, 200);
        for (MarketQuote q : new MarketQuote[]{noOi, negativeDepth}) {
            assertEquals(Status.BLOCKED_DATA,
                eval(CONTRACT, PORTFOLIO, q, RIGHTS, FUNDS, STRESS).status());
        }
    }

    @Test
    void unverifiedBrokerFundingAndZeroStressReserveFailClosed() {
        for (Funding f : new Funding[]{
            new Funding(d("300000"), d("80000"), d("20000"), false, true),
            new Funding(d("300000"), d("80000"), d("20000"), true, false),
            new Funding(d("300000"), d("80000"), d("0"), true, true),
            new Funding(d("-1"), d("80000"), d("20000"), true, true)
        }) {
            assertEquals("HEDGE_MARGIN_OR_COST_PROOF_MISSING",
                eval(CONTRACT, PORTFOLIO, QUOTE, RIGHTS, f, STRESS).reasonCode());
        }
    }

    @Test
    void invalidCostModelAndRiskThresholdsCannotBeDefaulted() {
        for (Stress s : new Stress[]{
            new Stress(500, d("1000"), d("500"), 10, 50, 3000, false, true),
            new Stress(500, d("1000"), d("500"), 10, 50, 3000, true, false),
            new Stress(500, d("0"), d("500"), 10, 50, 3000, true, true),
            new Stress(0, d("1000"), d("500"), 10, 50, 3000, true, true),
            new Stress(500, d("1000"), d("500"), 101, 50, 3000, true, true),
            new Stress(500, d("1000"), d("500"), 10, 50, 0, true, true)
        }) {
            assertEquals(Status.BLOCKED_DATA,
                eval(CONTRACT, PORTFOLIO, QUOTE, RIGHTS, FUNDS, s).status());
        }
        assertEquals(Status.BLOCKED_DATA,
            eval(CONTRACT, PORTFOLIO, QUOTE, RIGHTS, FUNDS, null).status());
    }

    @Test
    void sameAsOfSnapshotProducesDeterministicResearchOnlyOutcome() {
        assertEquals(eval(), eval());
        assertFalse(eval().executable());
        assertFalse(eval().orderPlaced());
        assertFalse(eval().liveTradeAuthorized());
    }
}
