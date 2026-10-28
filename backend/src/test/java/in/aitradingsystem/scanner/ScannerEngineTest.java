package in.aitradingsystem.scanner;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import in.aitradingsystem.marketdata.MarketCandle;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class ScannerEngineTest {
    private static final Instant BASE = Instant.parse("2026-10-28T03:45:00Z");

    @Test
    void producesDeterministicVolumeBreakoutCandidate() {
        List<MarketCandle> candles = breakoutCandles(BASE);
        ScanContext context = new ScanContext(1L, "RELIANCE-EQ", "NSE", "FIVE_MINUTE", BASE.plusSeconds(300), candles);
        ScannerEngine engine = new ScannerEngine(List.of(new VolumeBreakoutStrategy(), new MovingAverageReclaimStrategy()), Duration.ofMinutes(10));

        List<ScanCandidate> first = engine.scan(context);
        List<ScanCandidate> second = engine.scan(context);

        assertEquals(first, second);
        assertTrue(first.stream().anyMatch(candidate -> candidate.strategy().equals(VolumeBreakoutStrategy.NAME)));
        ScanCandidate breakout = first.stream().filter(candidate -> candidate.strategy().equals(VolumeBreakoutStrategy.NAME)).findFirst().orElseThrow();
        assertEquals(new BigDecimal("105.00"), breakout.referencePrice());
        assertTrue(breakout.suggestedStop().compareTo(breakout.referencePrice()) < 0);
        assertTrue(breakout.suggestedTarget().compareTo(breakout.referencePrice()) > 0);
        assertTrue(breakout.reasons().contains("RESEARCH_CANDIDATE_ONLY"));
    }

    @Test
    void rejectsFutureLatestCandleBeforeStrategiesRun() {
        List<MarketCandle> candles = breakoutCandles(BASE.plusSeconds(600));
        ScanContext context = new ScanContext(1L, "RELIANCE-EQ", "NSE", "FIVE_MINUTE", BASE, candles);
        ScannerEngine engine = new ScannerEngine(List.of(new VolumeBreakoutStrategy()), Duration.ofMinutes(10));

        assertTrue(engine.scan(context).isEmpty());
    }

    @Test
    void rejectsStaleSnapshot() {
        List<MarketCandle> candles = breakoutCandles(BASE);
        ScanContext context = new ScanContext(1L, "RELIANCE-EQ", "NSE", "FIVE_MINUTE", BASE.plus(Duration.ofHours(2)), candles);
        ScannerEngine engine = new ScannerEngine(List.of(new VolumeBreakoutStrategy()), Duration.ofMinutes(10));

        assertTrue(engine.scan(context).isEmpty());
    }

    @Test
    void insufficientHistoryProducesNoCandidate() {
        List<MarketCandle> candles = breakoutCandles(BASE).subList(0, 10);
        ScanContext context = new ScanContext(1L, "RELIANCE-EQ", "NSE", "FIVE_MINUTE", BASE.plusSeconds(300), candles);
        ScannerEngine engine = new ScannerEngine(List.of(new VolumeBreakoutStrategy()), Duration.ofMinutes(10));

        assertTrue(engine.scan(context).isEmpty());
    }

    private static List<MarketCandle> breakoutCandles(Instant latestTime) {
        List<MarketCandle> candles = new ArrayList<>();
        Instant first = latestTime.minusSeconds(20L * 300L);
        for (int i = 0; i < 20; i++) {
            BigDecimal close = new BigDecimal("100.00").add(BigDecimal.valueOf(i).multiply(new BigDecimal("0.05")));
            candles.add(candle(i + 1L, first.plusSeconds(i * 300L), close.subtract(new BigDecimal("0.20")),
                    close.add(new BigDecimal("0.30")), close.subtract(new BigDecimal("0.40")), close, 1_000L));
        }
        candles.add(candle(21L, latestTime, new BigDecimal("102.00"), new BigDecimal("106.00"),
                new BigDecimal("103.00"), new BigDecimal("105.00"), 2_000L));
        return List.copyOf(candles);
    }

    private static MarketCandle candle(long id, Instant time, BigDecimal open, BigDecimal high,
                                       BigDecimal low, BigDecimal close, long volume) {
        return new MarketCandle(id, 1L, "NSE", "RELIANCE-EQ", "2885", null, "FIVE_MINUTE",
                time, open, high, low, close, volume, "TEST_FIXTURE", time.plusSeconds(1));
    }
}
