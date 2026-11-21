package in.aitradingsystem.tradingrooms;

import static org.junit.jupiter.api.Assertions.*;

import in.aitradingsystem.tradingrooms.FnoBearCallCreditSpreadV1.Assumptions;
import in.aitradingsystem.tradingrooms.FnoBearCallCreditSpreadV1.CompletedClose;
import in.aitradingsystem.tradingrooms.FnoBearCallCreditSpreadV1.Contract;
import in.aitradingsystem.tradingrooms.FnoBearCallCreditSpreadV1.Decision;
import in.aitradingsystem.tradingrooms.FnoBearCallCreditSpreadV1.OptionQuote;
import in.aitradingsystem.tradingrooms.FnoBearCallCreditSpreadV1.SourceRights;
import in.aitradingsystem.tradingrooms.FnoBearCallCreditSpreadV1.Status;
import in.aitradingsystem.tradingrooms.FnoBearCallCreditSpreadV1.UnderlyingQuote;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class FnoBearCallCreditSpreadV1Test {
    static final Instant NOW = Instant.parse("2026-11-20T05:00:00Z");
    static final Instant TICK = NOW.minusSeconds(40);
    static final LocalDate EXPIRY = LocalDate.of(2026, 11, 27);
    static final String PROVIDER = "UPSTOX_ANALYTICS";
    static final String INDEX = FnoLongOptionCrossoverV1.UNDERLYING;
    static final String LOW = "NSE_FO|NIFTY_20261127_75_CE";
    static final String HIGH = "NSE_FO|NIFTY_20261127_80_CE";
    static final Contract LOWER = new Contract(LOW, INDEX, "CE", n("75"), n("0.05"),
        LocalDate.of(2026, 11, 1), EXPIRY, 50, true);
    static final Contract UPPER = new Contract(HIGH, INDEX, "CE", n("80"), n("0.05"),
        LocalDate.of(2026, 11, 1), EXPIRY, 50, true);
    static final SourceRights RIGHTS = new SourceRights(true, true, true);
    static final Assumptions COSTS = new Assumptions(50, 50, n("10"),
        300, 2, 50, 5000, n("10000"), 3000, 3000);
    static final UnderlyingQuote UNDERLYING = new UnderlyingQuote(PROVIDER, INDEX,
        n("75"), TICK, TICK.plusSeconds(10));
    static final OptionQuote LONG = new OptionQuote(PROVIDER, HIGH, n("1.95"), n("2.00"),
        10, 10, 1000, TICK, TICK.plusSeconds(10));
    static final OptionQuote SHORT = new OptionQuote(PROVIDER, LOW, n("5.00"), n("5.10"),
        10, 10, 1000, TICK, TICK.plusSeconds(10));
    static BigDecimal n(String n) { return new BigDecimal(n); }
    static List<CompletedClose> candles(String lastClose) {
        var dates = new ArrayList<LocalDate>();
        LocalDate day = LocalDate.of(2026, 11, 19);
        while (dates.size() < 35) {
            if (day.getDayOfWeek().getValue() <= 5) dates.add(0, day);
            day = day.minusDays(1);
        }
        var bars = new ArrayList<CompletedClose>();
        for (int i = 0; i < dates.size(); i++) {
            Instant close = dates.get(i).atTime(LocalTime.of(15,30))
                .atZone(ZoneId.of("Asia/Kolkata")).toInstant();
            bars.add(new CompletedClose(dates.get(i), PROVIDER, close, close.plusSeconds(60),
                n(i == 34 ? lastClose : "100"), true));
        }
        return bars;
    }
    static Decision evaluate(Contract l, Contract h, List<CompletedClose> b,
                             UnderlyingQuote u, OptionQuote ql, OptionQuote qh,
                             SourceRights r, Assumptions a) {
        return FnoBearCallCreditSpreadV1.evaluate(l, h, b, u, ql, qh, r, a, NOW);
    }
    static Decision run() {
        return evaluate(LOWER, UPPER, candles("75"), UNDERLYING, LONG, SHORT,
            RIGHTS, COSTS);
    }
    static void expect(Decision d, Status status, String reason) {
        assertEquals(status, d.status());
        assertEquals(reason, d.reasonCode());
        assertFalse(d.executable());
        assertFalse(d.orderPlaced());
        assertFalse(d.liveTradeAuthorized());
    }
    @Test void bearishCrossoverYieldsBoundedResearchSpreadOnly() {
        var d = run();
        assertEquals(Status.RESEARCH_CANDIDATE, d.status());
        assertEquals("strategy.STR-186", d.strategyId());
        assertEquals(HIGH, d.longCallKey());
        assertEquals(LOW, d.shortCallKey());
        assertTrue(d.creditPerUnit().signum() > 0);
        assertEquals(n("5.0000"), d.maxLossPerSpreadLotInr()
            .add(d.maxGainPerSpreadLotInr()).divide(n("50")));
        assertTrue(d.maxRewardRiskBps().compareTo(n("5000")) > 0);
        assertFalse(d.executable());
        assertFalse(d.orderPlaced());
        assertFalse(d.liveTradeAuthorized());
    }
    @Test void flatOrBearishCrossoverIsNoSignal() {
        for (String p : List.of("100", "125")) {
            expect(evaluate(LOWER, UPPER, candles(p), UNDERLYING, LONG, SHORT, RIGHTS, COSTS),
                Status.NO_SIGNAL, "BEAR_CALL_CREDIT_SPREAD_NEW_BEARISH_CROSSOVER_ABSENT");
        }
    }
    @Test void independentRightsMustBeVerified() {
        expect(evaluate(LOWER, UPPER, candles("75"), UNDERLYING, LONG, SHORT,
            new SourceRights(true, false, true), COSTS),
            Status.BLOCKED_RIGHTS, "BEAR_CALL_CREDIT_SPREAD_RIGHTS_MISSING");
    }
    @Test void wrongLegTypeLotExpiryOrStrikeOrderBlocked() {
        for (Contract c : List.of(
            new Contract(HIGH, INDEX, "PE", n("80"), n("0.05"),
                UPPER.listedFrom(), EXPIRY, 50, true),
            new Contract(HIGH, INDEX, "CE", n("80"), n("0.05"),
                UPPER.listedFrom(), EXPIRY, 25, true),
            new Contract(HIGH, INDEX, "CE", n("75"), n("0.05"),
                UPPER.listedFrom(), LocalDate.of(2026, 12, 20), 50, true),
            new Contract(HIGH, INDEX, "CE", n("65"), n("0.05"),
                UPPER.listedFrom(), EXPIRY, 50, true))) {
            expect(evaluate(LOWER, c, candles("75"), UNDERLYING, LONG, SHORT, RIGHTS, COSTS),
                Status.BLOCKED_DATA, "BEAR_CALL_CREDIT_SPREAD_CONTRACT_OR_COST_INVALID");
        }
    }
    @Test void missingOIOrDepthOrCrossedMarketRejected() {
        for (OptionQuote q : List.of(
            new OptionQuote(PROVIDER, LOW, n("5.00"), n("5.10"), 0, 10, 1000, TICK, TICK.plusSeconds(10)),
            new OptionQuote(PROVIDER, LOW, n("5.00"), n("5.10"), 10, 10, 0, TICK, TICK.plusSeconds(10)),
            new OptionQuote(PROVIDER, LOW, n("5.20"), n("5.10"), 10, 10, 1000, TICK, TICK.plusSeconds(10)))) {
            expect(evaluate(LOWER, UPPER, candles("75"), UNDERLYING, LONG, q, RIGHTS, COSTS),
                Status.BLOCKED_DATA, "BEAR_CALL_CREDIT_SPREAD_QUOTES_OR_ASOF_INVALID");
        }
    }
    @Test void providerMismatchAndCrossLegStalenessRejected() {
        var q = new OptionQuote("ANGEL_ONE_SMARTAPI", LOW, n("5.00"), n("5.10"),
            10, 10, 1000, TICK, TICK.plusSeconds(10));
        expect(evaluate(LOWER, UPPER, candles("75"), UNDERLYING, LONG, q,
            RIGHTS, COSTS), Status.BLOCKED_DATA, "BEAR_CALL_CREDIT_SPREAD_QUOTES_OR_ASOF_INVALID");
        q = new OptionQuote(PROVIDER, LOW, n("5.00"), n("5.10"),
            10, 10, 1000, TICK.minusSeconds(120), TICK.minusSeconds(100));
        expect(evaluate(LOWER, UPPER, candles("75"), UNDERLYING, LONG, q,
            RIGHTS, COSTS), Status.BLOCKED_DATA, "BEAR_CALL_CREDIT_SPREAD_QUOTES_OR_ASOF_INVALID");
    }
    @Test void historicalPublicationLookaheadBlocked() {
        var b = candles("75");
        var last = b.getLast();
        b.set(34, new CompletedClose(last.session(), PROVIDER, last.exchangeCloseAt(),
            NOW.minusSeconds(10), last.close(), true));
        expect(evaluate(LOWER, UPPER, b, UNDERLYING, LONG, SHORT, RIGHTS, COSTS),
            Status.BLOCKED_DATA, "BEAR_CALL_CREDIT_SPREAD_QUOTES_OR_ASOF_INVALID");
        b = candles("75");
        b.set(34, b.get(33));
        expect(evaluate(LOWER, UPPER, b, UNDERLYING, LONG, SHORT, RIGHTS, COSTS),
            Status.BLOCKED_DATA, "BEAR_CALL_CREDIT_SPREAD_HISTORY_INVALID");
    }
    @Test void uneconomicCreditRejected() {
        var noCredit = new OptionQuote(PROVIDER, LOW, n("1.98"), n("2.02"),
            10, 10, 1000, TICK, TICK.plusSeconds(10));
        expect(evaluate(LOWER, UPPER, candles("75"), UNDERLYING, LONG, noCredit,
            RIGHTS, COSTS), Status.NO_SIGNAL, "BEAR_CALL_CREDIT_SPREAD_CREDIT_NOT_ECONOMIC");
        var excessiveCredit = new OptionQuote(PROVIDER, LOW, n("9.00"), n("9.10"),
            10, 10, 1000, TICK, TICK.plusSeconds(10));
        expect(evaluate(LOWER, UPPER, candles("75"), UNDERLYING, LONG, excessiveCredit,
            RIGHTS, COSTS), Status.NO_SIGNAL, "BEAR_CALL_CREDIT_SPREAD_CREDIT_NOT_ECONOMIC");
    }
    @Test void premiumBudgetAndFeesAreGuarded() {
        var tinyBudget = new Assumptions(50, 50, n("10"), 300, 2, 50,
            5000, n("200"), 100, 3000);
        expect(evaluate(LOWER, UPPER, candles("75"), UNDERLYING, LONG, SHORT,
            RIGHTS, tinyBudget), Status.NO_SIGNAL, "BEAR_CALL_CREDIT_SPREAD_BUDGET_EXCEEDED");
        var noCosts = new Assumptions(50, 50, n("0"), 300, 2, 50,
            5000, n("10000"), 3000, 3000);
        expect(evaluate(LOWER, UPPER, candles("75"), UNDERLYING, LONG, SHORT,
            RIGHTS, noCosts), Status.BLOCKED_DATA, "BEAR_CALL_CREDIT_SPREAD_CONTRACT_OR_COST_INVALID");
    }
    @Test void maxRewardIsAfterFeesNotGuaranteedProfit() {
        var highFees = new Assumptions(50, 50, n("160"), 300, 2, 50,
            5000, n("10000"), 3000, 3000);
        expect(evaluate(LOWER, UPPER, candles("75"), UNDERLYING, LONG, SHORT,
            RIGHTS, highFees), Status.NO_SIGNAL,
            "BEAR_CALL_CREDIT_SPREAD_AFTER_COST_MAX_REWARD_TOO_LOW");
    }
    @Test void resultsDeterministicAndNeverOrders() {
        assertEquals(run(), run());
        assertFalse(run().orderPlaced());
    }
}
