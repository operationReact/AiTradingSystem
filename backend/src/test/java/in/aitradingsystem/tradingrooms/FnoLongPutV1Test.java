package in.aitradingsystem.tradingrooms;

import static org.junit.jupiter.api.Assertions.*;

import in.aitradingsystem.tradingrooms.FnoLongPutV1.Assumptions;
import in.aitradingsystem.tradingrooms.FnoLongPutV1.PutQuote;
import in.aitradingsystem.tradingrooms.FnoLongPutV1.CompletedClose;
import in.aitradingsystem.tradingrooms.FnoLongPutV1.Contract;
import in.aitradingsystem.tradingrooms.FnoLongPutV1.Decision;
import in.aitradingsystem.tradingrooms.FnoLongPutV1.SourceRights;
import in.aitradingsystem.tradingrooms.FnoLongPutV1.Status;
import in.aitradingsystem.tradingrooms.FnoLongPutV1.UnderlyingQuote;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class FnoLongPutV1Test {
    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    private static final Instant NOW = Instant.parse("2026-11-20T05:00:00Z");
    private static final Instant TICK = NOW.minusSeconds(40);
    private static final String PROVIDER = "UPSTOX_ANALYTICS";
    private static final String UNDERLYING = FnoLongOptionCrossoverV1.UNDERLYING;
    private static final String PE = "NSE_FO|NIFTY_20261127_75_PE";
    private static final LocalDate EXPIRY = LocalDate.of(2026, 11, 27);
    private static final Contract CONTRACT = new Contract(PE, UNDERLYING, "PE",
        n("75"), n("0.05"), LocalDate.of(2026, 11, 1), EXPIRY, 50, true);
    private static final SourceRights RIGHTS = new SourceRights(true, true, true);
    private static final Assumptions ASSUMPTIONS = new Assumptions(1000, 50, n("10"),
        10000, 300, 1000, 2, 50, n("10000"), 3000);
    private static final UnderlyingQuote SPOT = new UnderlyingQuote(PROVIDER,
        UNDERLYING, n("75"), TICK, TICK.plusSeconds(10));
    private static final PutQuote QUOTE = new PutQuote(PROVIDER, PE,
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
                                PutQuote q, SourceRights r, Assumptions a, Instant at) {
        return FnoLongPutV1.evaluate(c, b, u, q, r, a, at);
    }
    private static Decision run() {
        return run(bars("75"), CONTRACT, SPOT, QUOTE, RIGHTS, ASSUMPTIONS, NOW);
    }
    private static void expect(Decision d, Status status, String reason) {
        assertEquals(status, d.status());
        assertEquals(reason, d.reasonCode());
        assertFalse(d.executable());
        assertFalse(d.orderPlaced());
        assertFalse(d.liveTradeAuthorized());
    }

    @Test void bearishCrossoverWithVerifiedPutIsResearchCandidateOnly() {
        Decision d = run();
        assertEquals(Status.RESEARCH_CANDIDATE, d.status());
        assertEquals("strategy.STR-182", d.strategyId());
        assertEquals(PE, d.optionKey());
        assertEquals(EXPIRY, d.expiry());
        assertEquals(n("72.7900"), d.breakEvenAtExpiry());
        assertTrue(d.maxModelLossPerLotInr().signum() > 0);
        assertTrue(d.hypotheticalExpiryPnlPerLotInr().signum() > 0);
        assertTrue(d.hypotheticalRewardRiskBps().compareTo(n("10000")) > 0);
        assertFalse(d.executable());
        assertFalse(d.orderPlaced());
        assertFalse(d.liveTradeAuthorized());
    }

    @Test void bullishOrFlatUnderlyingDoesNotTriggerPut() {
        expect(run(bars("125"), CONTRACT, SPOT, QUOTE, RIGHTS, ASSUMPTIONS, NOW),
            Status.NO_SIGNAL, "LONG_PUT_NEW_BEARISH_CROSSOVER_ABSENT");
        expect(run(bars("100"), CONTRACT, SPOT, QUOTE, RIGHTS, ASSUMPTIONS, NOW),
            Status.NO_SIGNAL, "LONG_PUT_NEW_BEARISH_CROSSOVER_ABSENT");
    }

    @Test void missingResearchRightsFailClosed() {
        expect(run(bars("75"), CONTRACT, SPOT, QUOTE,
            new SourceRights(true, false, true), ASSUMPTIONS, NOW),
            Status.BLOCKED_RIGHTS, "LONG_PUT_SOURCE_RIGHTS_UNVERIFIED");
    }

    @Test void rejectsCallWrongUnderlyingAndUnverifiedContracts() {
        for (Contract c : List.of(
                new Contract(PE, UNDERLYING, "CE", n("75"), n("0.05"),
                    CONTRACT.listedFrom(), EXPIRY, 50, true),
                new Contract(PE, "NSE_EQ|OTHER", "PE", n("75"), n("0.05"),
                    CONTRACT.listedFrom(), EXPIRY, 50, true),
                new Contract(PE, UNDERLYING, "PE", n("75"), n("0.05"),
                    CONTRACT.listedFrom(), EXPIRY, 50, false))) {
            expect(run(bars("75"), c, SPOT, QUOTE, RIGHTS, ASSUMPTIONS, NOW),
                Status.BLOCKED_DATA, "LONG_PUT_CONTRACT_OR_ASSUMPTIONS_INVALID");
        }
    }

    @Test void rejectsExpiredOrDistantExpiry() {
        for (LocalDate expiry : List.of(LocalDate.of(2026, 11, 20),
                                        LocalDate.of(2027, 3, 20))) {
            Contract c = new Contract(PE, UNDERLYING, "PE", n("75"), n("0.05"),
                CONTRACT.listedFrom(), expiry, 50, true);
            expect(run(bars("75"), c, SPOT, QUOTE, RIGHTS, ASSUMPTIONS, NOW),
                Status.BLOCKED_DATA, "LONG_PUT_CONTRACT_OR_ASSUMPTIONS_INVALID");
        }
    }

    @Test void staleOrFutureKnownQuoteIsRejected() {
        var stale = new PutQuote(PROVIDER, PE, n("1.95"), n("2"), 10, 1000,
            NOW.minusSeconds(181), NOW.minusSeconds(180));
        expect(run(bars("75"), CONTRACT, SPOT, stale, RIGHTS, ASSUMPTIONS, NOW),
            Status.BLOCKED_DATA, "LONG_PUT_QUOTE_OR_ASOF_INVALID");
        var forward = new PutQuote(PROVIDER, PE, n("1.95"), n("2"), 10, 1000,
            TICK, NOW.plusSeconds(1));
        expect(run(bars("75"), CONTRACT, SPOT, forward, RIGHTS, ASSUMPTIONS, NOW),
            Status.BLOCKED_DATA, "LONG_PUT_QUOTE_OR_ASOF_INVALID");
    }

    @Test void crossProviderOrMismatchedPutKeyIsRejected() {
        for (PutQuote q : List.of(
                new PutQuote("ANGEL_ONE_SMARTAPI", PE, n("1.95"), n("2"),
                    10, 1000, TICK, TICK.plusSeconds(10)),
                new PutQuote(PROVIDER, "NSE_FO|WRONG", n("1.95"), n("2"),
                    10, 1000, TICK, TICK.plusSeconds(10)))) {
            expect(run(bars("75"), CONTRACT, SPOT, q, RIGHTS, ASSUMPTIONS, NOW),
                Status.BLOCKED_DATA, "LONG_PUT_QUOTE_OR_ASOF_INVALID");
        }
    }

    @Test void unsynchronizedLegsOrOutOfHoursQuoteIsRejected() {
        var lag = new PutQuote(PROVIDER, PE, n("1.95"), n("2"), 10, 1000,
            TICK.minusSeconds(100), TICK.minusSeconds(90));
        expect(run(bars("75"), CONTRACT, SPOT, lag, RIGHTS, ASSUMPTIONS, NOW),
            Status.BLOCKED_DATA, "LONG_PUT_QUOTE_OR_ASOF_INVALID");
        Instant night = Instant.parse("2026-11-20T18:00:00Z");
        var q = new PutQuote(PROVIDER, PE, n("1.95"), n("2"), 10, 1000,
            night, night.plusSeconds(10));
        expect(run(bars("75"), CONTRACT, SPOT, q, RIGHTS, ASSUMPTIONS,
            night.plusSeconds(40)), Status.BLOCKED_DATA, "LONG_PUT_QUOTE_OR_ASOF_INVALID");
    }

    @Test void crossedOrWideQuotesOrInsufficientLiquidityAreRejected() {
        for (PutQuote q : List.of(
                new PutQuote(PROVIDER, PE, n("2.10"), n("2.00"), 10, 1000,
                    TICK, TICK.plusSeconds(10)),
                new PutQuote(PROVIDER, PE, n("1.00"), n("3.00"), 10, 1000,
                    TICK, TICK.plusSeconds(10)),
                new PutQuote(PROVIDER, PE, n("1.95"), n("2"), 1, 1000,
                    TICK, TICK.plusSeconds(10)),
                new PutQuote(PROVIDER, PE, n("1.95"), n("2"), 10, 0,
                    TICK, TICK.plusSeconds(10)))) {
            expect(run(bars("75"), CONTRACT, SPOT, q, RIGHTS, ASSUMPTIONS, NOW),
                Status.BLOCKED_DATA, "LONG_PUT_QUOTE_OR_ASOF_INVALID");
        }
    }

    @Test void duplicateOrFutureKnownCompletedSessionRejected() {
        var b = bars("75");
        var last = b.getLast();
        b.set(34, new CompletedClose(last.session(), PROVIDER, last.exchangeCloseAt(),
            NOW.plusSeconds(1), last.close(), true));
        expect(run(b, CONTRACT, SPOT, QUOTE, RIGHTS, ASSUMPTIONS, NOW),
            Status.BLOCKED_DATA, "LONG_PUT_COMPLETED_CANDLES_INVALID");
        b = bars("75");
        b.set(34, b.get(33));
        expect(run(b, CONTRACT, SPOT, QUOTE, RIGHTS, ASSUMPTIONS, NOW),
            Status.BLOCKED_DATA, "LONG_PUT_COMPLETED_CANDLES_INVALID");
    }

    @Test void rejectHistoricalLookaheadIntoEarlierLiveQuote() {
        var b = bars("75");
        var last = b.getLast();
        b.set(34, new CompletedClose(last.session(), last.provider(),
            last.exchangeCloseAt(), NOW.minusSeconds(10), last.close(), true));
        expect(run(b, CONTRACT, SPOT, QUOTE, RIGHTS, ASSUMPTIONS, NOW),
            Status.BLOCKED_DATA, "LONG_PUT_QUOTE_OR_ASOF_INVALID");
    }

    @Test void expensivePutFailsHypotheticalExpiryHurdle() {
        var expensive = new PutQuote(PROVIDER, PE, n("19.80"), n("20"),
            10, 1000, TICK, TICK.plusSeconds(10));
        expect(run(bars("75"), CONTRACT, SPOT, expensive, RIGHTS, ASSUMPTIONS, NOW),
            Status.NO_SIGNAL, "LONG_PUT_EXPIRY_SCENARIO_AFTER_COSTS_INSUFFICIENT");
    }

    @Test void premiumAllocationCapRejectsEvenBearishSignal() {
        var a = new Assumptions(1000, 50, n("10"), 10000, 300, 1000, 2, 50,
            n("200"), 100);
        expect(run(bars("75"), CONTRACT, SPOT, QUOTE, RIGHTS, a, NOW),
            Status.NO_SIGNAL, "LONG_PUT_PREMIUM_EXCEEDS_RESEARCH_BUDGET");
    }

    @Test void invalidCostOrZeroCostFailsClosed() {
        for (String fee : List.of("-10", "0")) {
            var a = new Assumptions(1000, 50, n(fee), 10000, 300, 1000, 2, 50,
                n("10000"), 3000);
            expect(run(bars("75"), CONTRACT, SPOT, QUOTE, RIGHTS, a, NOW),
                Status.BLOCKED_DATA, "LONG_PUT_CONTRACT_OR_ASSUMPTIONS_INVALID");
        }
    }

    @Test void putIntrinsicCannotExceedStrikeEvenIfUnderlyingFallsToZero() {
        var a = new Assumptions(3000, 50, n("10"), 10000, 300, 1000, 2, 50,
            n("10000"), 3000);
        Decision d = run(bars("75"), CONTRACT, SPOT, QUOTE, RIGHTS, a, NOW);
        assertEquals(Status.RESEARCH_CANDIDATE, d.status());
        assertTrue(d.hypotheticalExpiryPnlPerLotInr()
            .compareTo(CONTRACT.strike().multiply(n("50"))) < 0);
        assertFalse(d.orderPlaced());
    }

    @Test void repeatEvaluationIsDeterministicAndNonExecutable() {
        assertEquals(run(), run());
        assertFalse(run().liveTradeAuthorized());
    }
}
