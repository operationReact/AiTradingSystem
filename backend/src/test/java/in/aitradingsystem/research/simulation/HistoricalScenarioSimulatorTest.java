package in.aitradingsystem.research.simulation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import in.aitradingsystem.marketdata.MarketCandle;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class HistoricalScenarioSimulatorTest {

    @Test
    void sameSeedProducesIdenticalResultAndCountsAllPaths() {
        ScenarioSimulationRequest request = request(2_000, 20, 4, 42L);
        List<MarketCandle> history = mixedHistory();
        HistoricalScenarioSimulator simulator = new HistoricalScenarioSimulator();

        ScenarioSimulationResult first = simulator.simulate(request, history);
        ScenarioSimulationResult second = simulator.simulate(request, history);

        assertEquals(first, second);
        assertEquals(2_000, first.targetFirstCount() + first.stopFirstCount() + first.neitherCount());
        assertEquals(1.0, first.targetFirstProbability() + first.stopFirstProbability() + first.neitherProbability(), 1e-12);
        assertEquals(HistoricalScenarioSimulator.METHODOLOGY, first.methodology());
        assertTrue(Double.isFinite(first.expectedR()));
    }

    @Test
    void bothLevelsTouchedInSameBarResolvesStopFirstConservatively() {
        ScenarioSimulationRequest request = request(500, 1, 1, 7L);
        List<MarketCandle> history = wideRangeHistory();

        ScenarioSimulationResult result = new HistoricalScenarioSimulator().simulate(request, history);

        assertEquals(0, result.targetFirstCount());
        assertEquals(500, result.stopFirstCount());
        assertEquals(0, result.neitherCount());
        assertEquals(1.0, result.stopFirstProbability());
        assertTrue(result.expectedR() < 0.0);
    }

    private static ScenarioSimulationRequest request(int simulations, int horizon, int block, long seed) {
        return new ScenarioSimulationRequest(
                1L, "FIVE_MINUTE", new BigDecimal("100"), new BigDecimal("95"), new BigDecimal("105"),
                Instant.parse("2026-01-01T00:00:00Z"), Instant.parse("2026-10-01T00:00:00Z"),
                simulations, horizon, block, seed, 5, 10);
    }

    private static List<MarketCandle> mixedHistory() {
        List<MarketCandle> bars = new ArrayList<>();
        Instant start = Instant.parse("2026-01-02T03:45:00Z");
        BigDecimal close = new BigDecimal("100");
        bars.add(candle(1, start, close, close.add(new BigDecimal("0.5")), close.subtract(new BigDecimal("0.5")), close));
        for (int i = 1; i < 80; i++) {
            BigDecimal move = switch (i % 5) {
                case 0 -> new BigDecimal("1.20");
                case 1 -> new BigDecimal("-0.80");
                case 2 -> new BigDecimal("0.35");
                case 3 -> new BigDecimal("-0.20");
                default -> new BigDecimal("0.55");
            };
            close = close.add(move).max(new BigDecimal("10"));
            bars.add(candle(i + 1L, start.plusSeconds(i * 300L), close,
                    close.add(new BigDecimal("0.90")), close.subtract(new BigDecimal("0.90")), close));
        }
        return List.copyOf(bars);
    }

    private static List<MarketCandle> wideRangeHistory() {
        List<MarketCandle> bars = new ArrayList<>();
        Instant start = Instant.parse("2026-01-02T03:45:00Z");
        for (int i = 0; i < 40; i++) {
            bars.add(candle(i + 1L, start.plusSeconds(i * 300L), new BigDecimal("100"),
                    new BigDecimal("110"), new BigDecimal("90"), new BigDecimal("100")));
        }
        return List.copyOf(bars);
    }

    private static MarketCandle candle(long id, Instant time, BigDecimal open, BigDecimal high,
                                       BigDecimal low, BigDecimal close) {
        return new MarketCandle(id, 1L, "NSE", "RELIANCE-EQ", "2885", null, "FIVE_MINUTE",
                time, open, high, low, close, 1_000L, "TEST_FIXTURE", time.plusSeconds(1));
    }
}
