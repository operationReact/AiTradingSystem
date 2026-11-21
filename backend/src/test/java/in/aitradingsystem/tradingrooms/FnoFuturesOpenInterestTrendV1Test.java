package in.aitradingsystem.tradingrooms;

import static org.junit.jupiter.api.Assertions.*;

import in.aitradingsystem.tradingrooms.FnoFuturesOpenInterestTrendV1.CompletedSession;
import in.aitradingsystem.tradingrooms.FnoFuturesOpenInterestTrendV1.Contract;
import in.aitradingsystem.tradingrooms.FnoFuturesOpenInterestTrendV1.Decision;
import in.aitradingsystem.tradingrooms.FnoFuturesOpenInterestTrendV1.SourceRights;
import in.aitradingsystem.tradingrooms.FnoFuturesOpenInterestTrendV1.Status;
import in.aitradingsystem.tradingrooms.FnoFuturesOpenInterestTrendV1.Thresholds;
import in.aitradingsystem.tradingrooms.FnoFuturesOpenInterestTrendV1.Trend;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class FnoFuturesOpenInterestTrendV1Test {
    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    private static final Instant AS_OF = Instant.parse("2026-11-20T11:00:00Z");
    private static final Contract CONTRACT = new Contract("NSE_FO|FUT_DEC_123",
        "NSE_INDEX|Nifty 50", LocalDate.of(2026, 12, 24), 75, true);
    private static final SourceRights RIGHTS = new SourceRights(true, true);
    private static final Thresholds THRESHOLDS = new Thresholds(800, 3, 2500, 5000);

    private static Instant close(LocalDate d) {
        LocalTime time = d.isBefore(LocalDate.of(2026, 8, 3)) ? LocalTime.of(15, 30)
            : d.isBefore(LocalDate.of(2026, 12, 7)) ? LocalTime.of(15, 40)
            : LocalTime.of(15, 45);
        return d.atTime(time).atZone(IST).toInstant();
    }
    private static CompletedSession day(int date, long oi, long volume) {
        LocalDate d = LocalDate.of(2026, 11, date);
        return new CompletedSession(d, true, CONTRACT.instrumentKey(),
            "UPSTOX_ANALYTICS", close(d).minusSeconds(30),
            close(d).plusSeconds(30 * 60), oi, volume);
    }
    private static List<CompletedSession> rising() {
        return List.of(day(16, 100000, 48000), day(17, 110000, 48000),
            day(18, 121000, 48000), day(19, 133000, 48000),
            day(20, 146000, 48000));
    }
    private static List<CompletedSession> declining() {
        return List.of(day(16, 150000, 50000), day(17, 138000, 50000),
            day(18, 125000, 50000), day(19, 113000, 50000),
            day(20, 100000, 50000));
    }
    private static Decision evaluate(Contract c, List<CompletedSession> bars,
                                     SourceRights rights, Thresholds t) {
        return FnoFuturesOpenInterestTrendV1.evaluate(c, bars, rights, t, AS_OF);
    }
    private static Decision evaluate(List<CompletedSession> bars) {
        return evaluate(CONTRACT, bars, RIGHTS, THRESHOLDS);
    }
    private static List<CompletedSession> change(List<CompletedSession> bars,
                                                  int index, CompletedSession row) {
        var replaced = new ArrayList<>(bars);
        replaced.set(index, row);
        return replaced;
    }

    @Test
    void risingOiProducesOnlyNonDirectionalResearchCandidate() {
        Decision d = evaluate(rising());
        assertEquals(Status.RESEARCH_CANDIDATE, d.status());
        assertEquals(Trend.RISING_OPEN_INTEREST, d.trend());
        assertEquals("SUSTAINED_OPEN_INTEREST_RISE", d.reasonCode());
        assertEquals("strategy.STR-173", d.strategyId());
        assertEquals(CONTRACT.instrumentKey(), d.instrumentKey());
        assertEquals(5, d.completedSessions());
        assertEquals(4, d.increasingSteps());
        assertEquals(0, d.decreasingSteps());
        assertTrue(d.cumulativeOiChangeBps().signum() > 0);
        assertTrue(d.minimumVolumeToOiBps().signum() > 0);
        assertFalse(d.executable());
        assertFalse(d.orderPlaced());
        assertFalse(d.directionalBuyOrSell());
    }

    @Test
    void fallingOiIsPositionClosureObservationNotBuySell() {
        Decision d = evaluate(declining());
        assertEquals(Status.RESEARCH_CANDIDATE, d.status());
        assertEquals(Trend.FALLING_OPEN_INTEREST, d.trend());
        assertEquals("SUSTAINED_OPEN_INTEREST_DECLINE", d.reasonCode());
        assertTrue(d.cumulativeOiChangeBps().signum() < 0);
        assertFalse(d.directionalBuyOrSell());
    }

    @Test
    void unchangedOiProducesNoTrend() {
        List<CompletedSession> bars = List.of(day(16, 100000, 48000),
            day(17, 100000, 48000), day(18, 100000, 48000),
            day(19, 100000, 48000), day(20, 100000, 48000));
        Decision d = evaluate(bars);
        assertEquals(Status.NO_SIGNAL, d.status());
        assertEquals(Trend.NONE, d.trend());
        assertEquals("OPEN_INTEREST_TREND_NOT_CONFIRMED", d.reasonCode());
    }

    @Test
    void threeIncreasingTransitionsAndOneFlatCanConfirmTrend() {
        var bars = List.of(day(16, 100000, 48000), day(17, 110000, 48000),
            day(18, 110000, 48000), day(19, 122000, 48000),
            day(20, 136000, 48000));
        Decision d = evaluate(bars);
        assertEquals(Status.RESEARCH_CANDIDATE, d.status());
        assertEquals(3, d.increasingSteps());
        assertEquals(0, d.decreasingSteps());
    }

    @Test
    void reversalDisqualifiesEvenWhenNetOiGrowthIsLarge() {
        var bars = List.of(day(16, 100000, 48000), day(17, 110000, 48000),
            day(18, 120000, 48000), day(19, 115000, 48000),
            day(20, 140000, 48000));
        assertEquals(Status.NO_SIGNAL, evaluate(bars).status());
    }

    @Test
    void insufficientDailyTurnoverCannotConfirmPositionTrend() {
        var r = rising();
        Decision d = evaluate(change(r, 4, day(20, 146000, 100)));
        assertEquals(Status.NO_SIGNAL, d.status());
        assertEquals("OI_SESSION_VOLUME_INSUFFICIENT", d.reasonCode());
    }

    @Test
    void oneSessionAbnormalOiJumpIsBlockedNotInventedAsNewSignal() {
        var bars = List.of(day(16, 100000, 48000), day(17, 200000, 80000),
            day(18, 220000, 80000), day(19, 240000, 80000),
            day(20, 260000, 80000));
        Decision d = evaluate(bars);
        assertEquals(Status.BLOCKED_DATA, d.status());
        assertEquals("OI_STEP_CHANGE_OUTLIER", d.reasonCode());
    }

    @Test
    void unverifiedResearchRightsOrDiscoveryFailClosed() {
        for (SourceRights bad : List.of(new SourceRights(false, true),
                                         new SourceRights(true, false))) {
            Decision d = evaluate(CONTRACT, rising(), bad, THRESHOLDS);
            assertEquals(Status.BLOCKED_RIGHTS, d.status());
            assertEquals("OI_SOURCE_RIGHTS_NOT_VERIFIED", d.reasonCode());
            assertNull(d.instrumentKey());
        }
    }

    @Test
    void nearExpiryWrongSegmentBadLotAndUnverifiedInstrumentBlock() {
        List<Contract> badContracts = List.of(
            new Contract("NSE_EQ|STOCK", CONTRACT.underlyingKey(),
                CONTRACT.expiry(), 75, true),
            new Contract(CONTRACT.instrumentKey(), CONTRACT.underlyingKey(),
                LocalDate.of(2026, 11, 24), 75, true),
            new Contract(CONTRACT.instrumentKey(), CONTRACT.underlyingKey(),
                CONTRACT.expiry(), 0, true),
            new Contract(CONTRACT.instrumentKey(), CONTRACT.underlyingKey(),
                CONTRACT.expiry(), 75, false));
        for (Contract c : badContracts) {
            assertEquals("OI_CONTRACT_IDENTITY_OR_EXPIRY_INVALID",
                evaluate(c, rising(), RIGHTS, THRESHOLDS).reasonCode());
        }
    }

    @Test
    void mismatchedInstrumentIdentityAcrossSessionsBlocksRolloverLeak() {
        var r = rising();
        var last = r.get(4);
        var otherContract = new CompletedSession(last.exchangeDate(), true,
            "NSE_FO|FUT_JAN_123", last.source(), last.exchangeAt(),
            last.observedAt(), last.openInterestUnits(), last.tradedVolumeUnits());
        assertEquals("OI_COMPLETED_SESSION_INVALID",
            evaluate(change(r, 4, otherContract)).reasonCode());
    }

    @Test
    void mixingBrokerSourcesAcrossHistoryBlocksTrend() {
        var r = rising();
        var last = r.get(4);
        var angel = new CompletedSession(last.exchangeDate(), true,
            last.instrumentKey(), "ANGEL_ONE_SMARTAPI", last.exchangeAt(),
            last.observedAt(), last.openInterestUnits(), last.tradedVolumeUnits());
        assertEquals("OI_SOURCE_CHANGED",
            evaluate(change(r, 4, angel)).reasonCode());
    }

    @Test
    void sessionMustBeCompleteAndReportedAfterClose() {
        var r = rising();
        var row = r.get(4);
        for (CompletedSession bad : List.of(
            new CompletedSession(row.exchangeDate(), false, row.instrumentKey(),
                row.source(), row.exchangeAt(), row.observedAt(),
                row.openInterestUnits(), row.tradedVolumeUnits()),
            new CompletedSession(row.exchangeDate(), true, row.instrumentKey(),
                row.source(), row.exchangeAt(), close(row.exchangeDate()).minusSeconds(1),
                row.openInterestUnits(), row.tradedVolumeUnits()),
            new CompletedSession(row.exchangeDate(), true, row.instrumentKey(),
                row.source(), close(row.exchangeDate()).plusSeconds(1), row.observedAt(),
                row.openInterestUnits(), row.tradedVolumeUnits()))) {
            assertEquals(Status.BLOCKED_DATA,
                evaluate(change(r, 4, bad)).status());
        }
    }

    @Test
    void futureKnownAndOverlongPublicationDelayCannotEnterBacktest() {
        var r = rising();
        var row = r.get(4);
        var future = new CompletedSession(row.exchangeDate(), true, row.instrumentKey(),
            row.source(), row.exchangeAt(), AS_OF.plusSeconds(1),
            row.openInterestUnits(), row.tradedVolumeUnits());
        assertEquals("OI_COMPLETED_SESSION_INVALID",
            evaluate(change(r, 4, future)).reasonCode());
        var old = r.get(0);
        var latePublication = new CompletedSession(old.exchangeDate(), true,
            old.instrumentKey(), old.source(), old.exchangeAt(),
            close(old.exchangeDate()).plusSeconds(37 * 3600),
            old.openInterestUnits(), old.tradedVolumeUnits());
        assertEquals(Status.BLOCKED_DATA,
            evaluate(change(r, 0, latePublication)).status());
    }

    @Test
    void zeroNegativeMissingOrOutOfRangeOiIsInvalidData() {
        var r = rising();
        var row = r.get(4);
        for (long badOi : new long[] {0, -1, 1_000_000_000_001L}) {
            var bad = new CompletedSession(row.exchangeDate(), true,
                row.instrumentKey(), row.source(), row.exchangeAt(),
                row.observedAt(), badOi, row.tradedVolumeUnits());
            assertEquals(Status.BLOCKED_DATA, evaluate(change(r, 4, bad)).status());
        }
    }

    @Test
    void duplicateOutOfOrderMissingOrGappedSessionsFail() {
        var r = rising();
        assertEquals(Status.BLOCKED_DATA,
            evaluate(List.of(r.get(0), r.get(1), r.get(1), r.get(3), r.get(4))).status());
        assertEquals(Status.BLOCKED_DATA,
            evaluate(List.of(r.get(0), r.get(2), r.get(1), r.get(3), r.get(4))).status());
        assertEquals(Status.BLOCKED_DATA, evaluate(r.subList(0, 4)).status());
        var gap = new CompletedSession(LocalDate.of(2026, 11, 9), true,
            r.get(0).instrumentKey(), r.get(0).source(),
            close(LocalDate.of(2026, 11, 9)).minusSeconds(30),
            close(LocalDate.of(2026, 11, 9)).plusSeconds(1800),
            r.get(0).openInterestUnits(), r.get(0).tradedVolumeUnits());
        assertEquals("OI_SESSION_SEQUENCE_INVALID",
            evaluate(List.of(gap, r.get(1), r.get(2), r.get(3), r.get(4))).reasonCode());
    }

    @Test
    void staleHistoryIsNotQualifiedAsCurrentTrend() {
        List<CompletedSession> old = List.of(day(9, 100000, 48000),
            day(10, 110000, 48000), day(11, 121000, 48000),
            day(12, 133000, 48000), day(13, 146000, 48000));
        assertEquals("OI_LAST_SESSION_STALE", evaluate(old).reasonCode());
    }

    @Test
    void invalidThresholdsAreNeverDefaulted() {
        assertEquals(Status.BLOCKED_DATA,
            evaluate(CONTRACT, rising(), RIGHTS, null).status());
        assertEquals(Status.BLOCKED_DATA,
            evaluate(CONTRACT, rising(), RIGHTS, new Thresholds(0, 3, 2500, 5000)).status());
        assertEquals(Status.BLOCKED_DATA,
            evaluate(CONTRACT, rising(), RIGHTS, new Thresholds(800, 5, 2500, 5000)).status());
        assertEquals(Status.BLOCKED_DATA,
            evaluate(CONTRACT, rising(), RIGHTS, new Thresholds(800, 3, 0, 5000)).status());
    }

    @Test
    void novemberFuturesCloseAt1540Rejects1530AsUncompletedOI() {
        var bars = rising();
        var row = bars.get(4);
        var premature = new CompletedSession(row.exchangeDate(), true,
            row.instrumentKey(), row.source(), close(row.exchangeDate()).minusSeconds(600),
            close(row.exchangeDate()).plusSeconds(1800),
            row.openInterestUnits(), row.tradedVolumeUnits());
        assertEquals("OI_COMPLETED_SESSION_INVALID",
            evaluate(change(bars, 4, premature)).reasonCode());
    }

    @Test
    void identicalAsOfInputIsReproducibleAndNeverExecutable() {
        assertEquals(evaluate(rising()), evaluate(rising()));
        assertFalse(evaluate(rising()).executable());
    }
}
