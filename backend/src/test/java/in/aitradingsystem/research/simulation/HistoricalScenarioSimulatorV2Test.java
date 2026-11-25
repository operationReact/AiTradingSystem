package in.aitradingsystem.research.simulation;

import static org.assertj.core.api.Assertions.assertThat;

import in.aitradingsystem.marketdata.MarketCandle;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class HistoricalScenarioSimulatorV2Test {
    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");

    @Test
    void sameSeedAndHistoryProduceExactlyEqualNormalizedResult() {
        List<MarketCandle> history = history();
        ScenarioSimulationRequest request = new ScenarioSimulationRequest(
                1L, "FIVE_MINUTE", new BigDecimal("100"), new BigDecimal("95"), new BigDecimal("106"),
                history.get(0).candleTime(), history.get(history.size() - 1).candleTime(),
                2_000, 24, 6, 99887766L, 5, 10);
        HistoricalScenarioSimulator simulator = new HistoricalScenarioSimulator();

        ScenarioSimulationResult first = simulator.simulate(request, history);
        ScenarioSimulationResult replay = simulator.simulate(request, history);

        assertThat(replay).isEqualTo(first);
        assertThat(first.methodology()).isEqualTo("SESSION_REGIME_BLOCK_BOOTSTRAP_V2");
        assertThat(first.fallbackTier()).isIn(
                BlockSamplingFallbackTier.EXACT_VOLATILITY_AND_VOLUME.name(),
                BlockSamplingFallbackTier.SAME_VOLATILITY.name(),
                BlockSamplingFallbackTier.ALL_ELIGIBLE.name());
        assertThat(first.eligibleBlockCount()).isPositive();
        assertThat(first.targetFirstCount() + first.stopFirstCount() + first.neitherCount()).isEqualTo(2_000);
    }

    private static List<MarketCandle> history() {
        List<MarketCandle> candles = new ArrayList<>();
        BigDecimal close = new BigDecimal("100");
        long id = 1;
        LocalDate first = LocalDate.of(2026, 1, 5);
        for (int day = 0; day < 5; day++) {
            Instant open = first.plusDays(day).atTime(9, 15).atZone(IST).toInstant();
            for (int bar = 0; bar < 60; bar++) {
                BigDecimal move = BigDecimal.valueOf(((bar * 3 + day) % 11 - 5) * 0.07d);
                close = close.add(move).max(new BigDecimal("20"));
                Instant time = open.plusSeconds(bar * 300L);
                candles.add(new MarketCandle(id, 1L, "NSE", "RELIANCE-EQ", "2885", null, "FIVE_MINUTE",
                        time, close, close.add(new BigDecimal("0.8")), close.subtract(new BigDecimal("0.8")), close,
                        1_000L + (id++ % 17) * 120L, "TEST_FIXTURE", time.plusSeconds(1)));
            }
        }
        return List.copyOf(candles);
    }
}
