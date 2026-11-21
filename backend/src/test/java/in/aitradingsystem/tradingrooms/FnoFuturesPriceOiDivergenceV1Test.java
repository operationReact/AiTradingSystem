package in.aitradingsystem.tradingrooms;

import static org.junit.jupiter.api.Assertions.*;

import in.aitradingsystem.tradingrooms.FnoFuturesPriceOiDivergenceV1.CompletedSession;
import in.aitradingsystem.tradingrooms.FnoFuturesPriceOiDivergenceV1.Contract;
import in.aitradingsystem.tradingrooms.FnoFuturesPriceOiDivergenceV1.Decision;
import in.aitradingsystem.tradingrooms.FnoFuturesPriceOiDivergenceV1.Divergence;
import in.aitradingsystem.tradingrooms.FnoFuturesPriceOiDivergenceV1.SourceRights;
import in.aitradingsystem.tradingrooms.FnoFuturesPriceOiDivergenceV1.Status;
import in.aitradingsystem.tradingrooms.FnoFuturesPriceOiDivergenceV1.Thresholds;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class FnoFuturesPriceOiDivergenceV1Test {
    private static final Instant AS_OF = Instant.parse("2026-11-20T11:00:00Z");
    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    private static final Contract CONTRACT = new Contract(
        "NSE_FO|DEC_1223", "NSE_INDEX|Nifty 50", LocalDate.of(2026, 12, 24),
        75, true);
    private static final SourceRights RIGHTS = new SourceRights(true, true, true);
    private static final Thresholds THRESHOLDS =
        new Thresholds(500, 500, 3, 2500, 2500, 5000);

    private static Instant close(LocalDate day) {
        LocalTime clock = day.isBefore(LocalDate.of(2026, 8, 3)) ? LocalTime.of(15, 30)
            : day.isBefore(LocalDate.of(2026, 12, 7)) ? LocalTime.of(15, 40)
            : LocalTime.of(15, 45);
        return day.atTime(clock).atZone(IST).toInstant();
    }

    private static CompletedSession day(int date, String price, long oi, long volume) {
        LocalDate d = LocalDate.of(2026, 11, date);
        return new CompletedSession(d, true, CONTRACT.instrumentKey(), "UPSTOX_ANALYTICS",
            new BigDecimal(price), true, close(d).plusSeconds(120),
            oi, volume, close(d).plusSeconds(900));
    }

    private static List<CompletedSession> priceUpOiDown() {
        return List.of(day(16, "100", 100000, 50000),
            day(17, "104", 95000, 50000), day(18, "108", 90000, 50000),
            day(19, "112", 85000, 50000), day(20, "116", 80000, 50000));
    }

    private static List<CompletedSession> priceDownOiUp() {
        return List.of(day(16, "116", 80000, 50000),
            day(17, "112", 85000, 50000), day(18, "108", 90000, 50000),
            day(19, "104", 95000, 50000), day(20, "100", 100000, 50000));
    }

    private static Decision run(Contract c, List<CompletedSession> rows,
                                SourceRights rights, Thresholds threshold) {
        return FnoFuturesPriceOiDivergenceV1.evaluate(c, rows, rights, threshold, AS_OF);
    }

    private static Decision run(List<CompletedSession> rows) {
        return run(CONTRACT, rows, RIGHTS, THRESHOLDS);
    }

    private static List<CompletedSession> replace(List<CompletedSession> rows,
                                                   int i, CompletedSession replacement) {
        var mutable = new ArrayList<>(rows);
        mutable.set(i, replacement);
        return mutable;
    }

    @Test
    void priceUpOiDownProducesObservedDivergenceWithoutTrade() {
        Decision d = run(priceUpOiDown());
        assertEquals(Status.RESEARCH_CANDIDATE, d.status());
        assertEquals(Divergence.PRICE_UP_OI_DOWN, d.divergence());
        assertEquals("PRICE_RISING_WITH_FALLING_OI", d.reasonCode());
        assertEquals("strategy.STR-174", d.strategyId());
        assertEquals(CONTRACT.instrumentKey(), d.instrumentKey());
        assertEquals("UPSTOX_ANALYTICS", d.source());
        assertEquals(5, d.completedSessions());
        assertEquals(4, d.upPriceDownOiSteps());
        assertEquals(0, d.downPriceUpOiSteps());
        assertTrue(d.cumulativePriceMoveBps().signum() > 0);
        assertTrue(d.cumulativeOiChangeBps().signum() < 0);
        assertFalse(d.executable());
        assertFalse(d.orderPlaced());
        assertFalse(d.directionalBuyOrSell());
    }

    @Test
    void priceDownOiUpIsDivergenceNotAutomaticShortTrade() {
        Decision d = run(priceDownOiUp());
        assertEquals(Status.RESEARCH_CANDIDATE, d.status());
        assertEquals(Divergence.PRICE_DOWN_OI_UP, d.divergence());
        assertEquals("PRICE_FALLING_WITH_RISING_OI", d.reasonCode());
        assertTrue(d.cumulativePriceMoveBps().signum() < 0);
        assertTrue(d.cumulativeOiChangeBps().signum() > 0);
        assertFalse(d.directionalBuyOrSell());
    }

    @Test
    void jointlyRisingPriceAndOiIsNotDivergence() {
        var bars = List.of(day(16, "100", 100000, 50000),
            day(17, "104", 105000, 50000), day(18, "108", 110000, 50000),
            day(19, "112", 115000, 50000), day(20, "116", 120000, 50000));
        assertEquals(Status.NO_SIGNAL, run(bars).status());
    }

    @Test
    void jointlyFallingPriceAndOiIsNotDivergence() {
        var bars = List.of(day(16, "116", 120000, 50000),
            day(17, "112", 115000, 50000), day(18, "108", 110000, 50000),
            day(19, "104", 105000, 50000), day(20, "100", 100000, 50000));
        assertEquals(Status.NO_SIGNAL, run(bars).status());
    }

    @Test
    void flatPriceAndOiProducesNoCandidate() {
        var bars = List.of(day(16, "100", 100000, 50000),
            day(17, "100", 100000, 50000), day(18, "100", 100000, 50000),
            day(19, "100", 100000, 50000), day(20, "100", 100000, 50000));
        assertEquals(Divergence.NONE, run(bars).divergence());
        assertEquals("PRICE_OI_DIVERGENCE_NOT_CONFIRMED", run(bars).reasonCode());
    }

    @Test
    void oneFlatStepIsAllowedWithThreeConfirmedDivergentSteps() {
        var bars = List.of(day(16, "100", 100000, 50000),
            day(17, "104", 95000, 50000), day(18, "104", 95000, 50000),
            day(19, "112", 85000, 50000), day(20, "116", 80000, 50000));
        assertEquals(Status.RESEARCH_CANDIDATE, run(bars).status());
        assertEquals(3, run(bars).upPriceDownOiSteps());
    }

    @Test
    void contraryPriceOiStepDisqualifiesEvenWhenCumulativeMovesDiverge() {
        var bars = List.of(day(16, "100", 100000, 50000),
            day(17, "104", 95000, 50000), day(18, "108", 97000, 50000),
            day(19, "112", 85000, 50000), day(20, "116", 80000, 50000));
        assertEquals(Status.NO_SIGNAL, run(bars).status());
    }

    @Test
    void insufficientVolumeToOpenInterestBlocksResearchSignal() {
        var bars = priceUpOiDown();
        Decision d = run(replace(bars, 4, day(20, "116", 80000, 1)));
        assertEquals(Status.NO_SIGNAL, d.status());
        assertEquals("PRICE_OI_VOLUME_PARTICIPATION_INSUFFICIENT", d.reasonCode());
    }

    @Test
    void priceOrOiOneStepOutlierRejectsQuestionableSourceSeries() {
        var rows = priceUpOiDown();
        assertEquals("PRICE_OI_ONE_STEP_OUTLIER", run(replace(rows, 4,
            day(20, "200", 80000, 50000))).reasonCode());
        assertEquals("PRICE_OI_ONE_STEP_OUTLIER", run(replace(rows, 4,
            day(20, "116", 10000, 50000))).reasonCode());
    }

    @Test
    void separateRightsForPriceOiAndContractDiscoveryAreMandatory() {
        for (SourceRights missing : List.of(
            new SourceRights(false, true, true),
            new SourceRights(true, false, true),
            new SourceRights(true, true, false))) {
            Decision d = run(CONTRACT, priceUpOiDown(), missing, THRESHOLDS);
            assertEquals(Status.BLOCKED_RIGHTS, d.status());
            assertEquals("PRICE_OI_SOURCE_RIGHTS_NOT_VERIFIED", d.reasonCode());
            assertNull(d.instrumentKey());
        }
    }

    @Test
    void invalidContractOrNearExpiryCannotBeAnalyzed() {
        for (Contract bad : List.of(
            new Contract("NSE_EQ|NOTFUTURES", CONTRACT.underlyingKey(),
                CONTRACT.expiry(), 75, true),
            new Contract(CONTRACT.instrumentKey(), CONTRACT.underlyingKey(),
                LocalDate.of(2026, 11, 24), 75, true),
            new Contract(CONTRACT.instrumentKey(), CONTRACT.underlyingKey(),
                CONTRACT.expiry(), 0, true),
            new Contract(CONTRACT.instrumentKey(), CONTRACT.underlyingKey(),
                CONTRACT.expiry(), 75, false))) {
            assertEquals("PRICE_OI_CONTRACT_INVALID", run(bad,
                priceUpOiDown(), RIGHTS, THRESHOLDS).reasonCode());
        }
    }

    @Test
    void switchingFuturesContractDuringSampleCannotBeMistakenForOiDivergence() {
        var rows = priceUpOiDown();
        CompletedSession last = rows.get(4);
        var moved = new CompletedSession(last.exchangeDate(), true,
            "NSE_FO|JAN_123", last.provider(), last.finalClosePrice(), true,
            last.finalPriceObservedAt(), last.finalizedOpenInterestUnits(),
            last.tradedVolumeUnits(), last.finalizedOiObservedAt());
        assertEquals("PRICE_OI_FINAL_SESSION_INVALID",
            run(replace(rows, 4, moved)).reasonCode());
    }

    @Test
    void priceAndOiMustComeFromSameSourceAcrossWholeWindow() {
        var rows = priceUpOiDown();
        CompletedSession last = rows.get(4);
        var switched = new CompletedSession(last.exchangeDate(), true,
            last.instrumentKey(), "ANGEL_ONE_SMARTAPI", last.finalClosePrice(), true,
            last.finalPriceObservedAt(), last.finalizedOpenInterestUnits(),
            last.tradedVolumeUnits(), last.finalizedOiObservedAt());
        assertEquals("PRICE_OI_PROVIDER_CHANGED",
            run(replace(rows, 4, switched)).reasonCode());
    }

    @Test
    void unverifiedFinalCloseOrUnverifiedSessionIsRejected() {
        var rows = priceUpOiDown();
        CompletedSession row = rows.get(4);
        for (var bad : List.of(
            new CompletedSession(row.exchangeDate(), false, row.instrumentKey(),
                row.provider(), row.finalClosePrice(), true, row.finalPriceObservedAt(),
                row.finalizedOpenInterestUnits(), row.tradedVolumeUnits(),
                row.finalizedOiObservedAt()),
            new CompletedSession(row.exchangeDate(), true, row.instrumentKey(),
                row.provider(), row.finalClosePrice(), false, row.finalPriceObservedAt(),
                row.finalizedOpenInterestUnits(), row.tradedVolumeUnits(),
                row.finalizedOiObservedAt()))) {
            assertEquals(Status.BLOCKED_DATA, run(replace(rows, 4, bad)).status());
        }
    }

    @Test
    void lookaheadAndPreCloseFinalOiAreRejected() {
        var rows = priceUpOiDown();
        CompletedSession row = rows.get(4);
        var ahead = new CompletedSession(row.exchangeDate(), true, row.instrumentKey(),
            row.provider(), row.finalClosePrice(), true, row.finalPriceObservedAt(),
            row.finalizedOpenInterestUnits(), row.tradedVolumeUnits(),
            AS_OF.plusSeconds(1));
        var beforeClose = new CompletedSession(row.exchangeDate(), true, row.instrumentKey(),
            row.provider(), row.finalClosePrice(), true, row.finalPriceObservedAt(),
            row.finalizedOpenInterestUnits(), row.tradedVolumeUnits(),
            close(row.exchangeDate()).minusSeconds(1));
        assertEquals(Status.BLOCKED_DATA, run(replace(rows, 4, ahead)).status());
        assertEquals(Status.BLOCKED_DATA, run(replace(rows, 4, beforeClose)).status());
    }

    @Test
    void invalidPriceOiAndVolumeAreNeverTreatedAsZeroEvidence() {
        var rows = priceUpOiDown();
        var row = rows.get(4);
        for (long badOi : new long[] {0, -5, 1_000_000_000_001L}) {
            var bad = new CompletedSession(row.exchangeDate(), true, row.instrumentKey(),
                row.provider(), row.finalClosePrice(), true, row.finalPriceObservedAt(),
                badOi, row.tradedVolumeUnits(), row.finalizedOiObservedAt());
            assertEquals(Status.BLOCKED_DATA, run(replace(rows, 4, bad)).status());
        }
        var badPrice = new CompletedSession(row.exchangeDate(), true,
            row.instrumentKey(), row.provider(), BigDecimal.ZERO,
            true, row.finalPriceObservedAt(), row.finalizedOpenInterestUnits(),
            row.tradedVolumeUnits(), row.finalizedOiObservedAt());
        assertEquals(Status.BLOCKED_DATA,
            run(replace(rows, 4, badPrice)).status());
    }

    @Test
    void duplicateOutOfOrderAndInsufficientSessionsAreRejected() {
        var r = priceUpOiDown();
        assertEquals(Status.BLOCKED_DATA,
            run(List.of(r.get(0), r.get(1), r.get(1), r.get(3), r.get(4))).status());
        assertEquals(Status.BLOCKED_DATA,
            run(List.of(r.get(0), r.get(2), r.get(1), r.get(3), r.get(4))).status());
        assertEquals(Status.BLOCKED_DATA, run(r.subList(0, 4)).status());
    }

    @Test
    void invalidThresholdsAreRejectedRatherThanDefaulted() {
        assertEquals(Status.BLOCKED_DATA, run(CONTRACT, priceUpOiDown(),
            RIGHTS, null).status());
        assertEquals(Status.BLOCKED_DATA, run(CONTRACT, priceUpOiDown(),
            RIGHTS, new Thresholds(0, 500, 3, 2500, 2500, 5000)).status());
        assertEquals(Status.BLOCKED_DATA, run(CONTRACT, priceUpOiDown(),
            RIGHTS, new Thresholds(500, 500, 5, 2500, 2500, 5000)).status());
    }

    @Test
    void str174NovemberFuturesFinalPriceKnownAt1535IsRejectedAfter1540CloseRule() {
        var rows = priceUpOiDown();
        CompletedSession row = rows.get(4);
        var premature = new CompletedSession(row.exchangeDate(), true,
            row.instrumentKey(), row.provider(), row.finalClosePrice(), true,
            close(row.exchangeDate()).minusSeconds(300),
            row.finalizedOpenInterestUnits(), row.tradedVolumeUnits(),
            row.finalizedOiObservedAt());
        assertEquals("PRICE_OI_FINAL_SESSION_INVALID",
            run(replace(rows, 4, premature)).reasonCode());
    }

    @Test
    void str174DecemberNormalCloseMustBe1545Not1540ForNonExpiringFutures() {
        Instant asOf = LocalDate.of(2026, 12, 11).atTime(17, 0).atZone(IST).toInstant();
        var decemberContract = new Contract(CONTRACT.instrumentKey(),
            CONTRACT.underlyingKey(), LocalDate.of(2027, 1, 28), 75, true);
        List<CompletedSession> dec = new ArrayList<>();
        for (int day = 7; day <= 11; day++) {
            LocalDate d = LocalDate.of(2026, 12, day);
            Instant closing = close(d);
            dec.add(new CompletedSession(d, true, decemberContract.instrumentKey(),
                "UPSTOX_ANALYTICS", new BigDecimal("100"), true,
                closing.plusSeconds(120), 100000L, 50000L, closing.plusSeconds(900)));
        }
        assertEquals(Status.NO_SIGNAL, FnoFuturesPriceOiDivergenceV1.evaluate(
            decemberContract, dec, RIGHTS, THRESHOLDS, asOf).status());
        CompletedSession last = dec.get(4);
        dec.set(4, new CompletedSession(last.exchangeDate(), true, last.instrumentKey(),
            last.provider(), last.finalClosePrice(), true,
            close(last.exchangeDate()).minusSeconds(60), last.finalizedOpenInterestUnits(),
            last.tradedVolumeUnits(), last.finalizedOiObservedAt()));
        assertEquals("PRICE_OI_FINAL_SESSION_INVALID",
            FnoFuturesPriceOiDivergenceV1.evaluate(
                decemberContract, dec, RIGHTS, THRESHOLDS, asOf).reasonCode());
    }

    @Test
    void validIdenticalInputsProduceIdenticalNonExecutableDecision() {
        assertEquals(run(priceUpOiDown()), run(priceUpOiDown()));
        assertFalse(run(priceDownOiUp()).orderPlaced());
    }
}
