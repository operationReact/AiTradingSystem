package in.aitradingsystem.scanner;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import in.aitradingsystem.marketdata.MarketCandle;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class ScannerEngineOutcomesTest {
    private static final Instant BASE = Instant.parse("2026-10-28T03:45:00Z");

    @Test
    void everyRegisteredStrategyGetsExactlyOneOutcome() {
        List<MarketCandle> candles = breakoutFixture();
        ScanContext context = new ScanContext(1L, "RELIANCE-EQ", "NSE", "FIVE_MINUTE", BASE.plusSeconds(300), candles);
        ScannerEngine engine = new ScannerEngine(
                List.of(new VolumeBreakoutStrategy(), new MovingAverageReclaimStrategy()), Duration.ofMinutes(10));

        List<ScannerEngine.StrategyEvaluation> evaluations = engine.evaluate(context);

        assertEquals(engine.strategyCodes(), evaluations.stream().map(ScannerEngine.StrategyEvaluation::strategy).toList());
        for (ScannerEngine.StrategyEvaluation e : evaluations) {
            assertTrue(List.of(ScannerEngine.CANDIDATE, ScannerEngine.NO_SIGNAL, ScannerEngine.NOT_EVALUATED).contains(e.outcome()));
            if (ScannerEngine.CANDIDATE.equals(e.outcome())) {
                assertNotNull(e.candidate());
            } else {
                assertNull(e.candidate());
            }
        }
        // The public scan contract is unchanged: it returns exactly the candidates from the evaluation.
        assertEquals(ScannerEngine.candidates(evaluations), engine.scan(context));
    }

    @Test
    void invalidSeriesMarksEveryStrategyNotEvaluatedWithAReason() {
        // Latest candle in the future is rejected before any strategy runs.
        List<MarketCandle> candles = ScannerEngineTest.breakoutCandles(BASE.plusSeconds(600));
        ScanContext context = new ScanContext(1L, "RELIANCE-EQ", "NSE", "FIVE_MINUTE", BASE, candles);
        ScannerEngine engine = new ScannerEngine(List.of(new VolumeBreakoutStrategy()), Duration.ofMinutes(10));

        List<ScannerEngine.StrategyEvaluation> evaluations = engine.evaluate(context);

        assertEquals(1, evaluations.size());
        assertEquals(ScannerEngine.NOT_EVALUATED, evaluations.getFirst().outcome());
        assertEquals("INVALID_CANDLE_SERIES", evaluations.getFirst().reasonCode());
    }

    @Test
    void shortHistoryIsNotEvaluatedRatherThanNoSignal() {
        // The most recent ten candles form a valid but short series.
        List<MarketCandle> all = breakoutFixture();
        List<MarketCandle> candles = all.subList(all.size() - 10, all.size());
        ScanContext context = new ScanContext(1L, "RELIANCE-EQ", "NSE", "FIVE_MINUTE", BASE.plusSeconds(300), candles);
        ScannerEngine engine = new ScannerEngine(List.of(new VolumeBreakoutStrategy()), Duration.ofMinutes(10));

        ScannerEngine.StrategyEvaluation only = engine.evaluate(context).getFirst();

        assertEquals(ScannerEngine.NOT_EVALUATED, only.outcome());
        assertEquals("INSUFFICIENT_HISTORY", only.reasonCode());
    }

    @Test
    void candidateOutcomeMatchesTheExistingScanOutput() {
        List<MarketCandle> candles = breakoutFixture();
        ScanContext context = new ScanContext(1L, "RELIANCE-EQ", "NSE", "FIVE_MINUTE", BASE.plusSeconds(300), candles);
        ScannerEngine engine = new ScannerEngine(List.of(new VolumeBreakoutStrategy()), Duration.ofMinutes(10));

        ScannerEngine.StrategyEvaluation only = engine.evaluate(context).getFirst();

        assertEquals(ScannerEngine.CANDIDATE, only.outcome());
        assertEquals("CANDIDATE", only.reasonCode());
        assertEquals(engine.scan(context).getFirst(), only.candidate());
    }

    private static List<MarketCandle> breakoutFixture() {
        return ScannerEngineTest.breakoutCandles(BASE);
    }
}
