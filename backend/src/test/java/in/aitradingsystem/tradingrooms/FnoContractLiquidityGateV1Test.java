package in.aitradingsystem.tradingrooms;

import static org.junit.jupiter.api.Assertions.*;

import in.aitradingsystem.tradingrooms.FnoContractLiquidityGateV1.Contract;
import in.aitradingsystem.tradingrooms.FnoContractLiquidityGateV1.Decision;
import in.aitradingsystem.tradingrooms.FnoContractLiquidityGateV1.DerivativeKind;
import in.aitradingsystem.tradingrooms.FnoContractLiquidityGateV1.MarketSnapshot;
import in.aitradingsystem.tradingrooms.FnoContractLiquidityGateV1.SourceRights;
import in.aitradingsystem.tradingrooms.FnoContractLiquidityGateV1.Status;
import in.aitradingsystem.tradingrooms.FnoContractLiquidityGateV1.Thresholds;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class FnoContractLiquidityGateV1Test {
    private static final Instant NOW = Instant.parse("2026-11-20T05:00:00Z");
    private static final String FUT = "NSE_FO|FUT_DEC_179";
    private static final Contract CONTRACT = new Contract(FUT, "NSE_INDEX|Nifty 50",
        LocalDate.of(2026, 12, 24), 25, DerivativeKind.FUTURE, null, true);
    private static final SourceRights RIGHTS = new SourceRights(true, true, true, true);
    private static final Thresholds LIMITS =
        new Thresholds(100, 20, 2, 20, 50, 5, 10, 30, 500);

    private static BigDecimal n(String value) { return new BigDecimal(value); }

    private static MarketSnapshot snap(int secondsAgo, String bid, String ask,
                                       long bidDepth, long askDepth, long oi, long volume) {
        return new MarketSnapshot(FUT, "UPSTOX_V3",
            NOW.minusSeconds(secondsAgo), NOW.minusSeconds(secondsAgo - 1),
            n(bid), n(ask), bidDepth, askDepth, oi, volume,
            NOW.minusSeconds(secondsAgo + 35),
            NOW.minusSeconds(secondsAgo - 1), true, true, true, true);
    }

    private static List<MarketSnapshot> series() {
        return List.of(
            snap(35, "20000", "20004", 100, 125, 20000, 5000),
            snap(20, "20001", "20005", 100, 125, 20000, 5050),
            snap(5, "20002", "20006", 100, 125, 20000, 5100));
    }

    private static List<MarketSnapshot> replace(List<MarketSnapshot> original, int i,
                                                 MarketSnapshot changed) {
        var copy = new ArrayList<>(original);
        copy.set(i, changed);
        return copy;
    }

    private static MarketSnapshot changed(MarketSnapshot old, String key, String provider,
                                          String bid, String ask, long bidDepth,
                                          long askDepth, long oi, long volume,
                                          Instant exchangeAt, Instant observedAt,
                                          Instant oiObserved, Instant volumeObserved,
                                          boolean sessionVerified, boolean quotesVerified,
                                          boolean oiVerified, boolean volumeVerified) {
        return new MarketSnapshot(key, provider, exchangeAt, observedAt,
            n(bid), n(ask), bidDepth, askDepth, oi, volume,
            oiObserved, volumeObserved, sessionVerified, quotesVerified,
            oiVerified, volumeVerified);
    }

    private static MarketSnapshot row(MarketSnapshot s, String key, String provider,
                                       String bid, String ask, long bidDepth, long askDepth,
                                       long oi, long volume) {
        return changed(s, key, provider, bid, ask, bidDepth, askDepth,
            oi, volume, s.exchangeAt(), s.observedAt(), s.oiFirstObservedAt(),
            s.volumeFirstObservedAt(), s.exchangeSessionVerified(), s.quoteVerified(),
            s.oiVerified(), s.volumeVerified());
    }

    private static MarketSnapshot timestamp(MarketSnapshot s, Instant exchangeAt,
                                             Instant observedAt, Instant oiObserved,
                                             Instant volumeObserved) {
        return changed(s, s.instrumentKey(), s.provider(), s.bid().toPlainString(),
            s.ask().toPlainString(), s.bidDepthUnits(), s.askDepthUnits(),
            s.openInterestUnits(), s.cumulativeVolumeUnits(),
            exchangeAt, observedAt, oiObserved, volumeObserved,
            s.exchangeSessionVerified(), s.quoteVerified(), s.oiVerified(),
            s.volumeVerified());
    }

    private static Decision run(Contract contract, List<MarketSnapshot> snapshots,
                                SourceRights rights, Thresholds limits) {
        return FnoContractLiquidityGateV1.evaluate(
            contract, snapshots, rights, limits, NOW);
    }

    private static Decision run(List<MarketSnapshot> rows) {
        return run(CONTRACT, rows, RIGHTS, LIMITS);
    }

    @Test
    void sufficientlyLiquidFuturePassesResearchScreenWithoutTrading() {
        Decision d = run(series());
        assertEquals(Status.RESEARCH_PASS, d.status());
        assertEquals("LIQUIDITY_RESEARCH_SCREEN_PASSED", d.reasonCode());
        assertEquals("strategy.STR-179", d.strategyId());
        assertEquals(FUT, d.instrumentKey());
        assertEquals("UPSTOX_V3", d.provider());
        assertEquals(3, d.inspectedSnapshots());
        assertTrue(d.maxObservedSpreadBps().compareTo(n("20")) < 0);
        assertTrue(d.estimatedRoundTripFrictionBps().compareTo(n("50")) < 0);
        assertEquals(0, d.latestOiLots().compareTo(n("800")));
        assertEquals(0, d.latestVolumeLots().compareTo(n("204")));
        assertEquals(100, d.minimumObservedBidDepthUnits());
        assertEquals(125, d.minimumObservedAskDepthUnits());
        assertFalse(d.executable());
        assertFalse(d.orderPlaced());
        assertFalse(d.liveTradeAuthorized());
    }

    @Test
    void providerVerifiedCallAndPutContractsCanBeScreened() {
        for (DerivativeKind kind : new DerivativeKind[] {
            DerivativeKind.CALL, DerivativeKind.PUT}) {
            Contract option = new Contract(FUT, CONTRACT.underlyingKey(),
                CONTRACT.expiry(), CONTRACT.lotSize(), kind, n("23000"), true);
            assertEquals(Status.RESEARCH_PASS,
                run(option, series(), RIGHTS, LIMITS).status());
        }
    }

    @Test
    void futuresWithStrikeAndOptionsWithMissingStrikeFailClosed() {
        for (Contract bad : new Contract[] {
            new Contract(FUT, CONTRACT.underlyingKey(), CONTRACT.expiry(),
                25, DerivativeKind.FUTURE, n("23000"), true),
            new Contract(FUT, CONTRACT.underlyingKey(), CONTRACT.expiry(),
                25, DerivativeKind.CALL, null, true),
            new Contract(FUT, CONTRACT.underlyingKey(), CONTRACT.expiry(),
                25, DerivativeKind.PUT, n("-10"), true)
        }) assertEquals("LIQUIDITY_CONTRACT_INVALID",
            run(bad, series(), RIGHTS, LIMITS).reasonCode());
    }

    @Test
    void invalidFutureIdentityLotExpiryAndUnverifiedInstrumentBlock() {
        for (Contract bad : new Contract[] {
            new Contract("NSE_EQ|NOT_FNO", CONTRACT.underlyingKey(),
                CONTRACT.expiry(), 25, DerivativeKind.FUTURE, null, true),
            new Contract(FUT, "UNKNOWN|INDEX", CONTRACT.expiry(),
                25, DerivativeKind.FUTURE, null, true),
            new Contract(FUT, CONTRACT.underlyingKey(), CONTRACT.expiry(),
                0, DerivativeKind.FUTURE, null, true),
            new Contract(FUT, CONTRACT.underlyingKey(),
                LocalDate.of(2026, 11, 19), 25, DerivativeKind.FUTURE, null, true),
            new Contract(FUT, CONTRACT.underlyingKey(), CONTRACT.expiry(),
                25, DerivativeKind.FUTURE, null, false)
        }) assertEquals(Status.BLOCKED_DATA,
            run(bad, series(), RIGHTS, LIMITS).status());
    }

    @Test
    void allQuoteOiVolumeAndDiscoveryResearchRightsAreIndependentlyRequired() {
        for (SourceRights bad : new SourceRights[]{
            null, new SourceRights(false, true, true, true),
            new SourceRights(true, false, true, true),
            new SourceRights(true, true, false, true),
            new SourceRights(true, true, true, false)
        }) {
            Decision d = run(CONTRACT, series(), bad, LIMITS);
            assertEquals(Status.BLOCKED_RIGHTS, d.status());
            assertNull(d.instrumentKey());
            assertEquals(0, d.inspectedSnapshots());
        }
    }

    @Test
    void wrongInstrumentAndUnrecognizedProviderAreRejected() {
        var a = series();
        MarketSnapshot s = a.get(2);
        for (MarketSnapshot bad : new MarketSnapshot[]{
            row(s, "NSE_FO|OTHER", s.provider(), "20002", "20006",
                100, 125, 20000, 5100),
            row(s, FUT, "SIMULATED", "20002", "20006",
                100, 125, 20000, 5100)
        }) assertEquals("LIQUIDITY_SNAPSHOT_PROVENANCE_INVALID",
            run(replace(a, 2, bad)).reasonCode());
    }

    @Test
    void mixingValidProvidersWithinOneQuoteSequenceIsNotPermitted() {
        var a = series();
        MarketSnapshot s = a.get(2);
        MarketSnapshot changed = row(s, FUT, "ANGEL_ONE_SMARTAPI", "20002",
            "20006", 100, 125, 20000, 5100);
        assertEquals("LIQUIDITY_PROVIDER_CHANGED",
            run(replace(a, 2, changed)).reasonCode());
    }

    @Test
    void missingSessionOrEachIndependentDataVerificationFlagFailsClosed() {
        var a = series();
        MarketSnapshot s = a.get(2);
        for (boolean[] flags : new boolean[][]{
            {false, true, true, true},
            {true, false, true, true},
            {true, true, false, true},
            {true, true, true, false}}) {
            MarketSnapshot bad = changed(s, FUT, "UPSTOX_V3", "20002", "20006",
                100, 125, 20000, 5100, s.exchangeAt(), s.observedAt(),
                s.oiFirstObservedAt(), s.volumeFirstObservedAt(),
                flags[0], flags[1], flags[2], flags[3]);
            assertEquals(Status.BLOCKED_DATA, run(replace(a, 2, bad)).status());
        }
    }

    @Test
    void crossedOrZeroBidAskAndInvalidMarketCountsAreBlockedData() {
        var a = series();
        MarketSnapshot s = a.get(2);
        for (MarketSnapshot bad : new MarketSnapshot[] {
            row(s, FUT, "UPSTOX_V3", "20010", "20000", 100, 125, 20000, 5100),
            row(s, FUT, "UPSTOX_V3", "0", "20000", 100, 125, 20000, 5100),
            row(s, FUT, "UPSTOX_V3", "20002", "20006", -1, 125, 20000, 5100),
            row(s, FUT, "UPSTOX_V3", "20002", "20006", 100, -1, 20000, 5100),
            row(s, FUT, "UPSTOX_V3", "20002", "20006", 100, 125, 0, 5100),
            row(s, FUT, "UPSTOX_V3", "20002", "20006", 100, 125, 20000, -1)
        }) assertEquals(Status.BLOCKED_DATA,
            run(replace(a, 2, bad)).status());
    }

    @Test
    void futureKnownAndBeforeExchangeTimestampObservationsFail() {
        var a = series();
        MarketSnapshot s = a.get(2);
        for (MarketSnapshot bad : new MarketSnapshot[]{
            timestamp(s, s.exchangeAt(), NOW.plusSeconds(1),
                s.oiFirstObservedAt(), s.volumeFirstObservedAt()),
            timestamp(s, NOW.plusSeconds(1), NOW.plusSeconds(2),
                s.oiFirstObservedAt(), s.volumeFirstObservedAt()),
            timestamp(s, s.exchangeAt(), s.exchangeAt().minusSeconds(1),
                s.oiFirstObservedAt(), s.volumeFirstObservedAt())
        }) assertEquals("LIQUIDITY_SNAPSHOT_PROVENANCE_INVALID",
            run(replace(a, 2, bad)).reasonCode());
    }

    @Test
    void futureKnownOiWithinAsOfButAfterQuoteObservationIsLookahead() {
        var a = series();
        MarketSnapshot s = a.get(2);
        // This OI value was first available AFTER the quote's observedAt,
        // even though it was known by evaluation asOf.
        MarketSnapshot leaked = timestamp(s, s.exchangeAt(), s.observedAt(),
            NOW.minusSeconds(2), s.volumeFirstObservedAt());
        assertEquals("LIQUIDITY_SNAPSHOT_PROVENANCE_INVALID",
            run(replace(a, 2, leaked)).reasonCode());
    }

    @Test
    void lastQuoteAgeFailsStrictFreshnessGate() {
        var a = List.of(
            snap(50, "20000", "20004", 100, 125, 20000, 5000),
            snap(35, "20001", "20005", 100, 125, 20000, 5050),
            snap(20, "20002", "20006", 100, 125, 20000, 5100));
        assertEquals("LIQUIDITY_QUOTE_STALE", run(a).reasonCode());
    }

    @Test
    void moreThanNinetySecondWindowIsRejectedEvenWhenLastQuoteIsFresh() {
        var a = List.of(
            snap(95, "20000", "20004", 100, 125, 20000, 5000),
            snap(50, "20001", "20005", 100, 125, 20000, 5050),
            snap(5, "20002", "20006", 100, 125, 20000, 5100));
        Thresholds gaps = new Thresholds(100, 20, 2, 20, 50, 5, 10, 45, 500);
        assertEquals("LIQUIDITY_QUOTE_STALE",
            run(CONTRACT, a, RIGHTS, gaps).reasonCode());
    }

    @Test
    void duplicateOutOfOrderOrGappedSnapshotTimestampsBlock() {
        var a = series();
        assertEquals("LIQUIDITY_SEQUENCE_OR_COUNTER_INVALID", run(List.of(
            a.get(0), a.get(0), a.get(2))).reasonCode());
        assertEquals(Status.BLOCKED_DATA, run(List.of(
            a.get(1), a.get(0), a.get(2))).status());
        assertEquals(Status.BLOCKED_DATA, run(List.of(
            a.get(0), a.get(2), a.get(1))).status());
        assertEquals(Status.BLOCKED_DATA, run(a.subList(0, 2)).status());
        var far = replace(a, 1, snap(50, "20001", "20005",
            100, 125, 20000, 5050));
        assertEquals(Status.BLOCKED_DATA, run(far).status());
    }

    @Test
    void cumulativeDayVolumeCannotGoBackwards() {
        var a = series();
        MarketSnapshot s = a.get(2);
        MarketSnapshot regressed = row(s, FUT, "UPSTOX_V3", "20002", "20006",
            100, 125, 20000, 4000);
        assertEquals("LIQUIDITY_SEQUENCE_OR_COUNTER_INVALID",
            run(replace(a, 2, regressed)).reasonCode());
    }

    @Test
    void openInterestAndVolumePublicationTimestampsMustNotGoBackwards() {
        var a = series();
        MarketSnapshot s = a.get(2);
        MarketSnapshot olderOi = timestamp(s, s.exchangeAt(), s.observedAt(),
            a.get(1).oiFirstObservedAt().minusSeconds(1), s.volumeFirstObservedAt());
        MarketSnapshot olderVolume = timestamp(s, s.exchangeAt(), s.observedAt(),
            s.oiFirstObservedAt(), a.get(1).volumeFirstObservedAt().minusSeconds(1));
        assertEquals(Status.BLOCKED_DATA,
            run(replace(a, 2, olderOi)).status());
        assertEquals(Status.BLOCKED_DATA,
            run(replace(a, 2, olderVolume)).status());
    }

    @Test
    void staleOpenInterestAndVolumePublicationFailClosed() {
        var a = series();
        MarketSnapshot s = a.get(2);
        MarketSnapshot oldOi = timestamp(s, s.exchangeAt(), s.observedAt(),
            NOW.minusSeconds(37 * 3600L), s.volumeFirstObservedAt());
        MarketSnapshot oldVolume = timestamp(s, s.exchangeAt(), s.observedAt(),
            s.oiFirstObservedAt(), NOW.minusSeconds(180));
        assertEquals("LIQUIDITY_SNAPSHOT_PROVENANCE_INVALID",
            run(replace(a, 2, oldOi)).reasonCode());
        assertEquals("LIQUIDITY_SNAPSHOT_PROVENANCE_INVALID",
            run(replace(a, 2, oldVolume)).reasonCode());
    }

    @Test
    void lowOpenInterestIsClassifiedAsIlliquid() {
        var a = series();
        var low = new ArrayList<MarketSnapshot>();
        for (MarketSnapshot s : a) {
            low.add(row(s, FUT, s.provider(), s.bid().toPlainString(),
                s.ask().toPlainString(), 100, 125, 1200,
                s.cumulativeVolumeUnits()));
        }
        Decision d = run(low);
        assertEquals(Status.ILLIQUID, d.status());
        assertEquals("LIQUIDITY_OPEN_INTEREST_TOO_LOW", d.reasonCode());
        assertFalse(d.executable());
    }

    @Test
    void insufficientSessionVolumeIsAnIlliquidResult() {
        var a = series();
        var low = new ArrayList<MarketSnapshot>();
        for (MarketSnapshot s : a) {
            low.add(row(s, FUT, s.provider(), s.bid().toPlainString(),
                s.ask().toPlainString(), 100, 125, 20000,
                s.cumulativeVolumeUnits() / 20));
        }
        assertEquals("LIQUIDITY_DAY_VOLUME_TOO_LOW", run(low).reasonCode());
    }

    @Test
    void thinBidOrAskDepthAtAnySnapshotRejectsContract() {
        var a = series();
        MarketSnapshot s = a.get(1);
        for (MarketSnapshot thin : new MarketSnapshot[]{
            row(s, FUT, "UPSTOX_V3", "20001", "20005", 25, 125, 20000, 5050),
            row(s, FUT, "UPSTOX_V3", "20001", "20005", 100, 25, 20000, 5050)
        }) {
            Decision d = run(replace(a, 1, thin));
            assertEquals(Status.ILLIQUID, d.status());
            assertEquals("LIQUIDITY_TWO_SIDED_DEPTH_TOO_LOW", d.reasonCode());
        }
    }

    @Test
    void wideSpreadRejectsContractDespiteHighVolumeAndDepth() {
        var a = series();
        MarketSnapshot s = a.get(2);
        MarketSnapshot wide = row(s, FUT, "UPSTOX_V3", "20002", "20082",
            100, 125, 20000, 5100);
        assertEquals("LIQUIDITY_SPREAD_TOO_WIDE",
            run(replace(a, 2, wide)).reasonCode());
    }

    @Test
    void quotedSpreadPlusImpactCanExceedFrictionEvenIfSpreadPasses() {
        Thresholds expensive = new Thresholds(
            100, 20, 2, 20, 10, 5, 10, 30, 500);
        assertEquals("LIQUIDITY_ESTIMATED_FRICTION_TOO_HIGH",
            run(CONTRACT, series(), RIGHTS, expensive).reasonCode());
    }

    @Test
    void suddenMidPriceChangeFailsAsUnverifiedQuoteJump() {
        var a = series();
        MarketSnapshot s = a.get(2);
        MarketSnapshot jump = row(s, FUT, "UPSTOX_V3", "23002", "23006",
            100, 125, 20000, 5100);
        assertEquals("LIQUIDITY_QUOTE_JUMP_UNVERIFIED",
            run(replace(a, 2, jump)).reasonCode());
    }

    @Test
    void invalidThresholdsAreNotAssumedOrDefaulted() {
        assertEquals(Status.BLOCKED_DATA,
            run(CONTRACT, series(), RIGHTS, null).status());
        assertEquals(Status.BLOCKED_DATA,
            run(CONTRACT, series(), RIGHTS,
                new Thresholds(0, 20, 2, 20, 50, 5, 10, 30, 500)).status());
        assertEquals(Status.BLOCKED_DATA,
            run(CONTRACT, series(), RIGHTS,
                new Thresholds(100, 20, 2, 20, 50, 5, 17, 30, 500)).status());
        assertEquals(Status.BLOCKED_DATA,
            run(CONTRACT, series(), RIGHTS,
                new Thresholds(100, 20, 2, 20, 50, 0, 10, 30, 500)).status());
    }

    @Test
    void deterministicResearchOnlyGateNeverApprovesExecution() {
        assertEquals(run(series()), run(series()));
        assertFalse(run(series()).executable());
        assertFalse(run(series()).orderPlaced());
        assertFalse(run(series()).liveTradeAuthorized());
    }
}
