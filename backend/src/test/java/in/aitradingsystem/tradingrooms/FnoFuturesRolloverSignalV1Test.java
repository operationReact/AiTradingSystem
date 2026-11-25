package in.aitradingsystem.tradingrooms;

import static org.junit.jupiter.api.Assertions.*;

import in.aitradingsystem.tradingrooms.FnoFuturesRolloverSignalV1.CompletedSession;
import in.aitradingsystem.tradingrooms.FnoFuturesRolloverSignalV1.Contract;
import in.aitradingsystem.tradingrooms.FnoFuturesRolloverSignalV1.Decision;
import in.aitradingsystem.tradingrooms.FnoFuturesRolloverSignalV1.LegSnapshot;
import in.aitradingsystem.tradingrooms.FnoFuturesRolloverSignalV1.SourceRights;
import in.aitradingsystem.tradingrooms.FnoFuturesRolloverSignalV1.Status;
import in.aitradingsystem.tradingrooms.FnoFuturesRolloverSignalV1.Thresholds;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.List;
import org.junit.jupiter.api.Test;

class FnoFuturesRolloverSignalV1Test {
    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    private static final Instant AS_OF = Instant.parse("2026-11-20T11:00:00Z");
    private static final String UNDERLYING = "NSE_INDEX|Nifty 50";
    private static final Contract NEAR = new Contract(
        "NSE_FO|NEAR_01", UNDERLYING, LocalDate.of(2026, 11, 26), 75, true);
    private static final Contract NEXT = new Contract(
        "NSE_FO|NEXT_02", UNDERLYING, LocalDate.of(2026, 12, 31), 75, true);
    private static final SourceRights RIGHTS = new SourceRights(true, true, true);
    private static final Thresholds THRESHOLDS =
        new Thresholds(1500, 1500, 1000, 4000, 12, 30);

    private static BigDecimal n(String v) { return new BigDecimal(v); }

    private static Instant close(LocalDate day) {
        return day.atTime(LocalTime.of(15, 30)).atZone(IST).toInstant();
    }

    private static LegSnapshot leg(String key, LocalDate day, long oi, long volume,
                                   String bid, String ask) {
        return new LegSnapshot(key, "UPSTOX_ANALYTICS",
            close(day).minusSeconds(35), close(day).plusSeconds(45),
            oi, volume, n(bid), n(ask));
    }

    private static CompletedSession session(int day, long nearOi, long farOi,
                                            long nearVolume, long farVolume) {
        LocalDate date = LocalDate.of(2026, 11, day);
        return new CompletedSession(date, true,
            leg(NEAR.instrumentKey(), date, nearOi, nearVolume, "25000", "25002"),
            leg(NEXT.instrumentKey(), date, farOi, farVolume, "25100", "25102"));
    }

    private static List<CompletedSession> migrating() {
        return List.of(
            session(18, 100000, 30000, 60000, 10000),
            session(19, 80000, 50000, 40000, 20000),
            session(20, 50000, 80000, 20000, 40000));
    }

    private static Decision eval(Contract near, Contract far, List<CompletedSession> data,
                                 SourceRights rights, Thresholds thresholds) {
        return FnoFuturesRolloverSignalV1.evaluate(near, far, data, rights, thresholds, AS_OF);
    }

    private static Decision eval(List<CompletedSession> data) {
        return eval(NEAR, NEXT, data, RIGHTS, THRESHOLDS);
    }

    @Test
    void confirmsTwoStepNearToFarOpenInterestMigrationButNeverBuySell() {
        Decision d = eval(migrating());
        assertEquals(Status.RESEARCH_CANDIDATE, d.status());
        assertEquals("NEAR_TO_NEXT_EXPIRY_OI_MIGRATION_OBSERVED", d.reasonCode());
        assertEquals("strategy.STR-172", d.strategyId());
        assertEquals(3, d.completedSessions());
        assertEquals(NEAR.expiry(), d.nearExpiry());
        assertEquals(NEXT.expiry(), d.farExpiry());
        assertTrue(d.nearOiDeclineBps().compareTo(n("1500")) > 0);
        assertTrue(d.farOiIncreaseBps().compareTo(n("1500")) > 0);
        assertTrue(d.farOiShareShiftBps().compareTo(n("1000")) > 0);
        assertTrue(d.farVolumeShareBps().compareTo(n("4000")) > 0);
        assertTrue(d.estimatedFrictionBps().compareTo(n("30")) < 0);
        assertFalse(d.executable());
        assertFalse(d.orderPlaced());
        assertFalse(d.directionalBuyOrSell());
    }

    @Test
    void fallingNearOiWithoutFarIncreaseIsNotRollover() {
        List<CompletedSession> data = List.of(
            session(18, 100000, 50000, 60000, 10000),
            session(19, 80000, 50000, 40000, 20000),
            session(20, 50000, 50000, 20000, 40000));
        assertEquals(Status.NO_SIGNAL, eval(data).status());
        assertEquals("TWO_STEP_OI_MIGRATION_NOT_CONFIRMED", eval(data).reasonCode());
    }

    @Test
    void risingFarOiWithoutDecliningNearOiIsNotRollover() {
        List<CompletedSession> data = List.of(
            session(18, 100000, 30000, 60000, 10000),
            session(19, 100000, 50000, 40000, 20000),
            session(20, 100000, 80000, 20000, 40000));
        assertEquals(Status.NO_SIGNAL, eval(data).status());
    }

    @Test
    void oneDayReversalDisqualifiesMigrationEvenIfEndPointsLookStrong() {
        List<CompletedSession> data = List.of(
            session(18, 100000, 30000, 60000, 10000),
            session(19, 90000, 25000, 40000, 20000),
            session(20, 50000, 80000, 20000, 40000));
        assertEquals(Status.NO_SIGNAL, eval(data).status());
    }

    @Test
    void littleFarExpiryTradingVolumeCannotConfirmRollover() {
        List<CompletedSession> baseline = migrating();
        CompletedSession last = session(20, 50000, 80000, 85000, 1000);
        assertEquals(Status.NO_SIGNAL, eval(List.of(baseline.get(0),
            baseline.get(1), last)).status());
    }

    @Test
    void excessiveTwoLegSpreadFrictionBlocksResearchCandidate() {
        var b = migrating();
        var last = b.get(2);
        LegSnapshot expensive = new LegSnapshot(
            last.far().instrumentKey(), last.far().source(),
            last.far().exchangeAt(), last.far().observedAt(),
            last.far().openInterestUnits(), last.far().sessionVolumeUnits(),
            n("25100"), n("25500"));
        Decision d = eval(List.of(b.get(0), b.get(1),
            new CompletedSession(last.sessionDate(), true, last.near(), expensive)));
        assertEquals(Status.NO_SIGNAL, d.status());
        assertEquals("ROLLOVER_ESTIMATED_FRICTION_TOO_HIGH", d.reasonCode());
        assertFalse(d.executable());
    }

    @Test
    void eachLegSourceRightAndContractDiscoveryAreMandatory() {
        for (SourceRights denied : List.of(
            new SourceRights(false, true, true),
            new SourceRights(true, false, true),
            new SourceRights(true, true, false))) {
            Decision d = eval(NEAR, NEXT, migrating(), denied, THRESHOLDS);
            assertEquals(Status.BLOCKED_RIGHTS, d.status());
            assertEquals("ROLLOVER_SOURCE_RIGHTS_NOT_VERIFIED", d.reasonCode());
            assertNull(d.farOiShareShiftBps());
        }
    }

    @Test
    void wrongUnderlyingLotSizeExpiryAndUnverifiedContractFailClosed() {
        for (Contract bad : List.of(
            new Contract(NEXT.instrumentKey(), "NSE_INDEX|Bank Nifty", NEXT.expiry(), 75, true),
            new Contract(NEXT.instrumentKey(), UNDERLYING, NEXT.expiry(), 50, true),
            new Contract(NEAR.instrumentKey(), UNDERLYING, NEXT.expiry(), 75, true),
            new Contract(NEXT.instrumentKey(), UNDERLYING, NEAR.expiry(), 75, true),
            new Contract(NEXT.instrumentKey(), UNDERLYING, NEXT.expiry(), 75, false),
            new Contract("NSE_EQ|NOT_FUTURE", UNDERLYING, NEXT.expiry(), 75, true))) {
            assertEquals("ROLLOVER_CONTRACT_PAIR_INVALID", eval(NEAR, bad,
                migrating(), RIGHTS, THRESHOLDS).reasonCode());
        }
    }

    @Test
    void signalOnlyInNearExpiryWindowAndWithMatchedFutureExpiries() {
        Contract notYet = new Contract(NEAR.instrumentKey(), UNDERLYING,
            LocalDate.of(2026, 12, 15), 75, true);
        Contract expiresTomorrow = new Contract(NEAR.instrumentKey(), UNDERLYING,
            LocalDate.of(2026, 11, 21), 75, true);
        for (Contract c : List.of(notYet, expiresTomorrow)) {
            assertEquals(Status.BLOCKED_DATA, eval(c, NEXT,
                migrating(), RIGHTS, THRESHOLDS).status());
        }
    }

    @Test
    void wrongKeyProviderChangeAndUnverifiedSessionAreRejected() {
        var list = migrating();
        CompletedSession last = list.get(2);
        LegSnapshot other = new LegSnapshot(
            last.far().instrumentKey(), "ANGEL_ONE_SMARTAPI",
            last.far().exchangeAt(), last.far().observedAt(),
            last.far().openInterestUnits(), last.far().sessionVolumeUnits(),
            last.far().bid(), last.far().ask());
        assertEquals(Status.BLOCKED_DATA, eval(List.of(list.get(0), list.get(1),
            new CompletedSession(last.sessionDate(), true, last.near(), other))).status());
        LegSnapshot wrongKey = new LegSnapshot(
            NEAR.instrumentKey(), last.far().source(),
            last.far().exchangeAt(), last.far().observedAt(),
            last.far().openInterestUnits(), last.far().sessionVolumeUnits(),
            last.far().bid(), last.far().ask());
        assertEquals(Status.BLOCKED_DATA, eval(List.of(list.get(0), list.get(1),
            new CompletedSession(last.sessionDate(), true, last.near(), wrongKey))).status());
        assertEquals(Status.BLOCKED_DATA, eval(List.of(list.get(0), list.get(1),
            new CompletedSession(last.sessionDate(), false, last.near(), last.far()))).status());
    }

    @Test
    void futureKnownOrBeforeSessionCloseDataCannotLeakIntoAsOfEvaluation() {
        var x = migrating();
        CompletedSession last = x.get(2);
        LegSnapshot futureKnown = new LegSnapshot(
            last.near().instrumentKey(), last.near().source(),
            last.near().exchangeAt(), AS_OF.plusSeconds(1),
            last.near().openInterestUnits(), last.near().sessionVolumeUnits(),
            last.near().bid(), last.near().ask());
        LegSnapshot incomplete = new LegSnapshot(
            last.near().instrumentKey(), last.near().source(),
            last.near().exchangeAt(), close(last.sessionDate()).minusSeconds(1),
            last.near().openInterestUnits(), last.near().sessionVolumeUnits(),
            last.near().bid(), last.near().ask());
        for (var bad : List.of(futureKnown, incomplete)) {
            assertEquals("ROLLOVER_SESSION_PROVENANCE_INVALID",
                eval(List.of(x.get(0), x.get(1),
                    new CompletedSession(last.sessionDate(), true, bad, last.far()))).reasonCode());
        }
    }

    @Test
    void negativeOiInvalidBidAndQuoteTimeSkewAreRejected() {
        var x = migrating();
        CompletedSession last = x.get(2);
        for (LegSnapshot bad : List.of(
            new LegSnapshot(last.near().instrumentKey(), last.near().source(),
                last.near().exchangeAt(), last.near().observedAt(),
                -1, last.near().sessionVolumeUnits(), last.near().bid(), last.near().ask()),
            new LegSnapshot(last.near().instrumentKey(), last.near().source(),
                last.near().exchangeAt(), last.near().observedAt(),
                last.near().openInterestUnits(), last.near().sessionVolumeUnits(), n("25100"), n("25000")),
            new LegSnapshot(last.near().instrumentKey(), last.near().source(),
                close(last.sessionDate()).minusSeconds(500),
                last.near().observedAt(), last.near().openInterestUnits(),
                last.near().sessionVolumeUnits(), last.near().bid(), last.near().ask()))) {
            assertEquals(Status.BLOCKED_DATA, eval(List.of(x.get(0), x.get(1),
                new CompletedSession(last.sessionDate(), true, bad, last.far()))).status());
        }
    }

    @Test
    void duplicateOutOfOrderAndGappedSessionsAreRejected() {
        var x = migrating();
        assertEquals(Status.BLOCKED_DATA, eval(List.of(x.get(0), x.get(0), x.get(2))).status());
        assertEquals(Status.BLOCKED_DATA, eval(List.of(x.get(1), x.get(0), x.get(2))).status());
        assertEquals(Status.BLOCKED_DATA, eval(List.of(x.get(0), x.get(2), x.get(1))).status());
        assertEquals(Status.BLOCKED_DATA, eval(List.of(x.get(0), x.get(1))).status());
    }

    @Test
    void invalidThresholdsAreNotAssumedOrDefaulted() {
        assertEquals("ROLLOVER_HISTORY_OR_THRESHOLDS_INVALID",
            eval(NEAR, NEXT, migrating(), RIGHTS, null).reasonCode());
        assertEquals(Status.BLOCKED_DATA, eval(NEAR, NEXT, migrating(),
            RIGHTS, new Thresholds(0, 1500, 1000, 4000, 12, 30)).status());
        assertEquals(Status.BLOCKED_DATA, eval(NEAR, NEXT, migrating(),
            RIGHTS, new Thresholds(1500, 1500, 1000, 4000, 100, 30)).status());
    }

    @Test
    void sameAsOfProviderSnapshotsAreReproducible() {
        assertEquals(eval(migrating()), eval(migrating()));
    }
}
