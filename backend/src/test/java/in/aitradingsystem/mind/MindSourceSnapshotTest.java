package in.aitradingsystem.mind;

import static org.junit.jupiter.api.Assertions.*;

import in.aitradingsystem.marketdata.MarketCandle;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class MindSourceSnapshotTest {
    private static final Instant SIGNAL = Instant.parse("2026-11-09T08:50:00Z");
    private static final Instant CAPTURE = SIGNAL.plusSeconds(60);
    private static final UUID CATALOGUE = UUID.randomUUID();

    private List<MarketCandle> recorded() {
        List<MarketCandle> result = new ArrayList<>();
        for (int i = 0; i < 21; i++) {
            result.add(new MarketCandle(
                i + 1L, 42L, "NSE", "INFY-EQ", "1234", CATALOGUE,
                "FIVE_MINUTE", SIGNAL.minusSeconds((20 - i) * 300L),
                new BigDecimal("100"), new BigDecimal("101"),
                new BigDecimal("99"), new BigDecimal("100"), 1000,
                "ANGEL_ONE_SMARTAPI", SIGNAL.minusSeconds(120)));
        }
        return result;
    }

    private List<MarketCandle> recordedOpeningRange() {
        List<MarketCandle> result = new ArrayList<>();
        for (int i = 0; i < MindSourceSnapshot.OPENING_RANGE_BARS; i++) {
            result.add(new MarketCandle(
                i + 1L, 42L, "NSE", "INFY-EQ", "1234", CATALOGUE,
                "ONE_MINUTE", SIGNAL.minusSeconds((MindSourceSnapshot.OPENING_RANGE_BARS - 1L - i) * 60L),
                new BigDecimal("100"), new BigDecimal("101"),
                new BigDecimal("99"), new BigDecimal("100"), 1000,
                "ANGEL_ONE_SMARTAPI", SIGNAL.minusSeconds(120)));
        }
        return result;
    }

    private MindSourceSnapshot freeze(List<MarketCandle> bars) {
        return MindSourceSnapshot.fromCandles(
            bars, 42L, "NSE", "INFY-EQ", "FIVE_MINUTE", SIGNAL, CAPTURE);
    }

    @Test
    void sameRecordedSourceProducesIdenticalVersionedSha256() {
        var original = recorded();
        var first = freeze(original);
        var second = freeze(List.copyOf(original));
        assertEquals(first, second);
        assertEquals(21, first.candleCount());
        assertTrue(first.digest().matches("[a-f0-9]{64}"));
        assertEquals(SIGNAL, first.lastCandleAt());
        assertTrue(first.canonicalInput().startsWith("MINT_MA_RECLAIM_SOURCE_V1"));
    }

    @Test
    void openingRangeSourceIsCapturedAsAnIndependentExact64BarContract() {
        var bars = recordedOpeningRange();
        var first = MindSourceSnapshot.fromOpeningRangeCandles(
            bars, 42L, "NSE", "INFY-EQ", "ONE_MINUTE", SIGNAL, CAPTURE);
        var second = MindSourceSnapshot.fromOpeningRangeCandles(
            List.copyOf(bars), 42L, "NSE", "INFY-EQ", "ONE_MINUTE", SIGNAL, CAPTURE);

        assertEquals(first, second);
        assertEquals(MindSourceSnapshot.OPENING_RANGE_BARS, first.candleCount());
        assertEquals(SIGNAL, first.lastCandleAt());
        assertTrue(first.canonicalInput().startsWith("MINT_ORB_SOURCE_V1"));
        assertNotEquals(first.digest(), freeze(recorded()).digest());
    }

    @Test
    void openingRangeCaptureRejectsWrongIntervalCountAndFutureFetches() {
        var bars = recordedOpeningRange();
        assertThrows(IllegalArgumentException.class, () ->
            MindSourceSnapshot.fromOpeningRangeCandles(
                bars, 42L, "NSE", "INFY-EQ", "FIVE_MINUTE", SIGNAL, CAPTURE));
        assertThrows(IllegalArgumentException.class, () ->
            MindSourceSnapshot.fromOpeningRangeCandles(
                bars.subList(1, bars.size()), 42L, "NSE", "INFY-EQ", "ONE_MINUTE", SIGNAL, CAPTURE));

        var revised = new ArrayList<>(bars);
        var last = revised.getLast();
        revised.set(revised.size() - 1, new MarketCandle(
            last.id(), last.instrumentId(), last.exchange(), last.tradingSymbol(),
            last.symbolToken(), last.catalogueVersion(), last.interval(), last.candleTime(),
            last.open(), last.high(), last.low(), last.close(), last.volume(),
            last.source(), CAPTURE.plusSeconds(1)));
        assertThrows(IllegalArgumentException.class, () ->
            MindSourceSnapshot.fromOpeningRangeCandles(
                revised, 42L, "NSE", "INFY-EQ", "ONE_MINUTE", SIGNAL, CAPTURE));
    }

    @Test
    void minorUnrelatedCandleRevisionChangesDigest() {
        var bars = recorded();
        var current = bars.get(0);
        bars.set(0, new MarketCandle(
            current.id(), current.instrumentId(), current.exchange(),
            current.tradingSymbol(), current.symbolToken(),
            current.catalogueVersion(), current.interval(), current.candleTime(),
            new BigDecimal("100.0001"), current.high(), current.low(), current.close(),
            current.volume(), current.source(), current.fetchedAt()));
        var changed = freeze(bars);
        assertNotEquals(changed.digest(), freeze(recorded()).digest());
        assertNotEquals(changed.canonicalInput(), freeze(recorded()).canonicalInput());
    }

    @Test
    void futureFetchedTimestampCannotBeCapturedRetroactively() {
        var bars = recorded();
        var current = bars.getLast();
        bars.set(20, new MarketCandle(
            current.id(), current.instrumentId(), current.exchange(),
            current.tradingSymbol(), current.symbolToken(), current.catalogueVersion(),
            current.interval(), current.candleTime(), current.open(), current.high(),
            current.low(), current.close(), current.volume(), current.source(),
            CAPTURE.plusSeconds(1)));
        assertThrows(IllegalArgumentException.class, () -> freeze(bars));
    }

    @Test
    void rejectFixturesMissingIdentityAndReorderedBars() {
        var bars = recorded();
        var fixture = bars.getLast();
        bars.set(20, new MarketCandle(
            fixture.id(), fixture.instrumentId(), fixture.exchange(),
            fixture.tradingSymbol(), fixture.symbolToken(), fixture.catalogueVersion(),
            fixture.interval(), fixture.candleTime(), fixture.open(), fixture.high(),
            fixture.low(), fixture.close(), fixture.volume(), "TEST_FIXTURE",
            fixture.fetchedAt()));
        assertThrows(IllegalArgumentException.class, () -> freeze(bars));

        var reversed = recorded().reversed();
        assertThrows(IllegalArgumentException.class, () -> freeze(reversed));
        assertThrows(IllegalArgumentException.class, () -> freeze(recorded().subList(1, 21)));
    }
}
