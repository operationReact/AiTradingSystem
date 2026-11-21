package in.aitradingsystem.tradingrooms;

import static org.junit.jupiter.api.Assertions.*;

import in.aitradingsystem.tradingrooms.FnoLongCallV1.Assumptions;
import in.aitradingsystem.tradingrooms.FnoLongCallV1.CallQuote;
import in.aitradingsystem.tradingrooms.FnoLongCallV1.CompletedClose;
import in.aitradingsystem.tradingrooms.FnoLongCallV1.Contract;
import in.aitradingsystem.tradingrooms.FnoLongCallV1.Decision;
import in.aitradingsystem.tradingrooms.FnoLongCallV1.SourceRights;
import in.aitradingsystem.tradingrooms.FnoLongCallV1.Status;
import in.aitradingsystem.tradingrooms.FnoLongCallV1.UnderlyingQuote;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class FnoLongCallV1Test {
    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    private static final Instant NOW = Instant.parse("2026-11-20T05:00:00Z");
    private static final Instant TICK = NOW.minusSeconds(40);
    private static final String PROVIDER = "UPSTOX_ANALYTICS";
    private static final String UNDERLYING = FnoLongOptionCrossoverV1.UNDERLYING;
    private static final String CE = "NSE_FO|NIFTY_20261127_125_CE";
    private static final LocalDate EXPIRY = LocalDate.of(2026, 11, 27);
    private static final Contract CONTRACT = new Contract(CE, UNDERLYING, "CE",
        n("125"), n("0.05"), LocalDate.of(2026, 11, 1), EXPIRY, 50, true);
    private static final SourceRights RIGHTS = new SourceRights(true, true, true);
    private static final Assumptions ASSUMPTIONS = new Assumptions(500, 50, n("10"),
        10000, 300, 1000, 2, 50, n("10000"), 3000);
    private static final UnderlyingQuote SPOT = new UnderlyingQuote(PROVIDER,
        UNDERLYING, n("125"), TICK, TICK.plusSeconds(10));
    private static final CallQuote QUOTE = new CallQuote(PROVIDER, CE,
        n("1.95"), n("2.00"), 10, 1000, TICK, TICK.plusSeconds(10));

    private static BigDecimal n(String x) { return new BigDecimal(x); }
    private static Instant close(LocalDate day) {
        return day.atTime(LocalTime.of(15, 30)).atZone(IST).toInstant();
    }
    private static List<CompletedClose> bars(String finalClose) {
        var dates = new ArrayList<LocalDate>();
        LocalDate d = LocalDate.of(2026, 11, 19);
        while (dates.size() < 35) {
            if (d.getDayOfWeek().getValue() <= 5) dates.add(0, d);
            d = d.minusDays(1);
        }
        var bars = new ArrayList<CompletedClose>();
        for (int i = 0; i < dates.size(); i++) {
            Instant at = close(dates.get(i));
            bars.add(new CompletedClose(dates.get(i), PROVIDER, at,
                at.plusSeconds(120), n(i == 34 ? finalClose : "100"), true));
        }
        return bars;
    }
    private static Decision run(List<CompletedClose> b, Contract c, UnderlyingQuote u,
                                CallQuote q, SourceRights r, Assumptions a, Instant at) {
        return FnoLongCallV1.evaluate(c, b, u, q, r, a, at);
    }
    private static Decision run() {
        return run(bars("125"), CONTRACT, SPOT, QUOTE, RIGHTS, ASSUMPTIONS, NOW);
    }
    private static void blocked(Decision d, Status status, String reason) {
        assertEquals(status, d.status());
        assertEquals(reason, d.reasonCode());
        assertFalse(d.executable());
        assertFalse(d.orderPlaced());
        assertFalse(d.liveTradeAuthorized());
    }

    @Test void eligibleBullishCrossoverAndRealisticScenarioAreResearchOnly() {
        Decision d = run();
        assertEquals(Status.RESEARCH_CANDIDATE, d.status());
        assertEquals("strategy.STR-181", d.strategyId());
        assertEquals(CE, d.optionKey());
        assertEquals(EXPIRY, d.expiry());
        assertEquals(n("127.2100"), d.breakEvenAtExpiry());
        assertTrue(d.maxModelLossPerLotInr().signum() > 0);
        assertTrue(d.hypotheticalExpiryPnlPerLotInr().signum() > 0);
        assertTrue(d.hypotheticalRewardRiskBps().compareTo(n("10000")) > 0);
        assertFalse(d.executable());
        assertFalse(d.orderPlaced());
        assertFalse(d.liveTradeAuthorized());
    }

    @Test void bearishOrFlatUnderlyingDoesNotCreateLongCall() {
        blocked(run(bars("75"), CONTRACT, SPOT, QUOTE, RIGHTS, ASSUMPTIONS, NOW),
            Status.NO_SIGNAL, "LONG_CALL_NEW_BULLISH_CROSSOVER_ABSENT");
        blocked(run(bars("100"), CONTRACT, SPOT, QUOTE, RIGHTS, ASSUMPTIONS, NOW),
            Status.NO_SIGNAL, "LONG_CALL_NEW_BULLISH_CROSSOVER_ABSENT");
    }

    @Test void researchRightsFailBeforeSignalAndOrdersRemainDisabled() {
        blocked(run(bars("125"), CONTRACT, SPOT, QUOTE,
            new SourceRights(true, false, true), ASSUMPTIONS, NOW),
            Status.BLOCKED_RIGHTS, "LONG_CALL_SOURCE_RIGHTS_UNVERIFIED");
    }

    @Test void rejectsPutAndWrongUnderlyingAndUnverifiedContract() {
        for (Contract c : List.of(
                new Contract(CE, UNDERLYING, "PE", n("125"), n("0.05"),
                    CONTRACT.listedFrom(), EXPIRY, 50, true),
                new Contract(CE, "NSE_EQ|OTHER", "CE", n("125"), n("0.05"),
                    CONTRACT.listedFrom(), EXPIRY, 50, true),
                new Contract(CE, UNDERLYING, "CE", n("125"), n("0.05"),
                    CONTRACT.listedFrom(), EXPIRY, 50, false))) {
            blocked(run(bars("125"), c, SPOT, QUOTE, RIGHTS, ASSUMPTIONS, NOW),
                Status.BLOCKED_DATA, "LONG_CALL_CONTRACT_OR_ASSUMPTIONS_INVALID");
        }
    }

    @Test void rejectsExpiredOrVeryDistantContracts() {
        for (LocalDate expiry : List.of(LocalDate.of(2026, 11, 20),
                                        LocalDate.of(2027, 3, 20))) {
            Contract c = new Contract(CE, UNDERLYING, "CE", n("125"), n("0.05"),
                CONTRACT.listedFrom(), expiry, 50, true);
            blocked(run(bars("125"), c, SPOT, QUOTE, RIGHTS, ASSUMPTIONS, NOW),
                Status.BLOCKED_DATA, "LONG_CALL_CONTRACT_OR_ASSUMPTIONS_INVALID");
        }
    }

    @Test void rejectsStaleOrFutureKnownQuote() {
        var old = new CallQuote(PROVIDER, CE, n("1.95"), n("2"), 10, 1000,
            NOW.minusSeconds(181), NOW.minusSeconds(180));
        blocked(run(bars("125"), CONTRACT, SPOT, old, RIGHTS, ASSUMPTIONS, NOW),
            Status.BLOCKED_DATA, "LONG_CALL_QUOTE_OR_ASOF_INVALID");
        var future = new CallQuote(PROVIDER, CE, n("1.95"), n("2"), 10, 1000,
            TICK, NOW.plusSeconds(1));
        blocked(run(bars("125"), CONTRACT, SPOT, future, RIGHTS, ASSUMPTIONS, NOW),
            Status.BLOCKED_DATA, "LONG_CALL_QUOTE_OR_ASOF_INVALID");
    }

    @Test void rejectsCrossProviderAndMismatchedOptionKeys() {
        for (CallQuote q : List.of(
                new CallQuote("ANGEL_ONE_SMARTAPI", CE, n("1.95"), n("2"),
                    10, 1000, TICK, TICK.plusSeconds(10)),
                new CallQuote(PROVIDER, "NSE_FO|WRONG", n("1.95"), n("2"),
                    10, 1000, TICK, TICK.plusSeconds(10)))) {
            blocked(run(bars("125"), CONTRACT, SPOT, q, RIGHTS, ASSUMPTIONS, NOW),
                Status.BLOCKED_DATA, "LONG_CALL_QUOTE_OR_ASOF_INVALID");
        }
    }

    @Test void rejectsUnsynchronizedLegsAndClosedMarketTime() {
        var lag = new CallQuote(PROVIDER, CE, n("1.95"), n("2"), 10, 1000,
            TICK.minusSeconds(100), TICK.minusSeconds(90));
        blocked(run(bars("125"), CONTRACT, SPOT, lag, RIGHTS, ASSUMPTIONS, NOW),
            Status.BLOCKED_DATA, "LONG_CALL_QUOTE_OR_ASOF_INVALID");
        Instant night = Instant.parse("2026-11-20T18:00:00Z");
        var nightQuote = new CallQuote(PROVIDER, CE, n("1.95"), n("2"), 10, 1000,
            night, night.plusSeconds(10));
        blocked(run(bars("125"), CONTRACT, SPOT, nightQuote, RIGHTS, ASSUMPTIONS,
            night.plusSeconds(40)), Status.BLOCKED_DATA, "LONG_CALL_QUOTE_OR_ASOF_INVALID");
    }

    @Test void rejectsCrossedOrExcessSpreadOrMissingDepthOrOpenInterest() {
        for (CallQuote q : List.of(
                new CallQuote(PROVIDER, CE, n("2.10"), n("2.00"), 10, 1000,
                    TICK, TICK.plusSeconds(10)),
                new CallQuote(PROVIDER, CE, n("1.00"), n("3.00"), 10, 1000,
                    TICK, TICK.plusSeconds(10)),
                new CallQuote(PROVIDER, CE, n("1.95"), n("2.00"), 1, 1000,
                    TICK, TICK.plusSeconds(10)),
                new CallQuote(PROVIDER, CE, n("1.95"), n("2.00"), 10, 0,
                    TICK, TICK.plusSeconds(10)))) {
            blocked(run(bars("125"), CONTRACT, SPOT, q, RIGHTS, ASSUMPTIONS, NOW),
                Status.BLOCKED_DATA, "LONG_CALL_QUOTE_OR_ASOF_INVALID");
        }
    }

    @Test void rejectsInvalidAsOfCompletedClosesAndDuplicateSessions() {
        var b = bars("125");
        CompletedClose last = b.getLast();
        b.set(34, new CompletedClose(last.session(), PROVIDER, last.exchangeCloseAt(),
            NOW.plusSeconds(1), last.close(), true));
        blocked(run(b, CONTRACT, SPOT, QUOTE, RIGHTS, ASSUMPTIONS, NOW),
            Status.BLOCKED_DATA, "LONG_CALL_COMPLETED_CANDLES_INVALID");
        b = bars("125");
        b.set(34, b.get(33));
        blocked(run(b, CONTRACT, SPOT, QUOTE, RIGHTS, ASSUMPTIONS, NOW),
            Status.BLOCKED_DATA, "LONG_CALL_COMPLETED_CANDLES_INVALID");
    }

    @Test void preventsUsingCompletedCandleBeforeProviderActuallyPublishedIt() {
        var b = bars("125");
        var last = b.getLast();
        // Completed candle was released AFTER the quoted option and spot ticks.
        b.set(34, new CompletedClose(last.session(), last.provider(),
            last.exchangeCloseAt(), NOW.minusSeconds(10), last.close(), true));
        blocked(run(b, CONTRACT, SPOT, QUOTE, RIGHTS, ASSUMPTIONS, NOW),
            Status.BLOCKED_DATA, "LONG_CALL_QUOTE_OR_ASOF_INVALID");
    }

    @Test void insufficientHypotheticalExpiryPayoffDoesNotPass() {
        var expensive = new CallQuote(PROVIDER, CE, n("19.80"), n("20.00"),
            10, 1000, TICK, TICK.plusSeconds(10));
        // Quote is research-valid only if bid/ask and moneyness are valid.
        blocked(run(bars("125"), CONTRACT, SPOT, expensive, RIGHTS, ASSUMPTIONS, NOW),
            Status.NO_SIGNAL, "LONG_CALL_EXPIRY_SCENARIO_AFTER_COSTS_INSUFFICIENT");
    }

    @Test void premiumRiskMayExceedAllocatedCapitalEvenWithBullishSignal() {
        var a = new Assumptions(500, 50, n("10"), 10000, 300, 1000, 2, 50,
            n("200"), 100);
        blocked(run(bars("125"), CONTRACT, SPOT, QUOTE, RIGHTS, a, NOW),
            Status.NO_SIGNAL, "LONG_CALL_PREMIUM_EXCEEDS_RESEARCH_BUDGET");
    }

    @Test void invalidEconomicAssumptionsFailClosed() {
        var a = new Assumptions(500, 50, n("-10"), 10000, 300, 1000, 2, 50,
            n("10000"), 3000);
        blocked(run(bars("125"), CONTRACT, SPOT, QUOTE, RIGHTS, a, NOW),
            Status.BLOCKED_DATA, "LONG_CALL_CONTRACT_OR_ASSUMPTIONS_INVALID");
    }

    @Test void missingCostsCannotBeTreatedAsFreeTrading() {
        var a = new Assumptions(500, 50, n("0"), 10000, 300, 1000, 2, 50,
            n("10000"), 3000);
        blocked(run(bars("125"), CONTRACT, SPOT, QUOTE, RIGHTS, a, NOW),
            Status.BLOCKED_DATA, "LONG_CALL_CONTRACT_OR_ASSUMPTIONS_INVALID");
    }

    @Test void deterministicRepeatedEvaluationDoesNotPlaceOrders() {
        assertEquals(run(), run());
        assertFalse(run().liveTradeAuthorized());
    }
}
