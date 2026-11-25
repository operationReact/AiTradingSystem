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

class HistoricalBlockSamplerTest {
    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");

    @Test
    void producesExactLengthBlocksAndAppliesDeclaredFallbackTier() {
        List<MarketCandle> history = history(LocalDate.of(2026, 1, 5), 4, 50);
        Instant cutoff = history.get(history.size() - 1).candleTime();

        HistoricalBlockSampler.SamplingPool pool = new HistoricalBlockSampler().select(history, cutoff, 5);

        assertThat(pool.blocks()).isNotEmpty();
        assertThat(pool.blocks()).allSatisfy(block -> {
            assertThat(block.observations()).hasSize(5);
            assertThat(block.startCandleTime()).isBeforeOrEqualTo(cutoff);
            assertThat(block.sourceTradeDate()).isEqualTo(block.startCandleTime().atZone(IST).toLocalDate());
        });
        assertThat(pool.eligibleBlockCount()).isEqualTo(pool.blocks().size());
        assertThat(pool.allEligibleBlockCount()).isGreaterThanOrEqualTo(pool.eligibleBlockCount());

        switch (pool.fallbackTier()) {
            case EXACT_VOLATILITY_AND_VOLUME -> assertThat(pool.blocks()).allSatisfy(block -> {
                assertThat(block.regimeSnapshot().volatilityRegime()).isEqualTo(pool.targetRegime().volatilityRegime());
                assertThat(block.regimeSnapshot().volumeRegime()).isEqualTo(pool.targetRegime().volumeRegime());
            });
            case SAME_VOLATILITY -> assertThat(pool.blocks()).allSatisfy(block ->
                    assertThat(block.regimeSnapshot().volatilityRegime()).isEqualTo(pool.targetRegime().volatilityRegime()));
            case ALL_ELIGIBLE -> assertThat(pool.blocks()).hasSize(pool.allEligibleBlockCount());
        }
    }

    private static List<MarketCandle> history(LocalDate firstDate, int days, int barsPerDay) {
        List<MarketCandle> candles = new ArrayList<>();
        BigDecimal close = new BigDecimal("100.00");
        long id = 1;
        for (int day = 0; day < days; day++) {
            LocalDate date = firstDate.plusDays(day);
            Instant open = date.atTime(9, 15).atZone(IST).toInstant();
            for (int bar = 0; bar < barsPerDay; bar++) {
                BigDecimal move = BigDecimal.valueOf(((bar + day) % 7 - 3) * 0.11d);
                close = close.add(move).max(new BigDecimal("20"));
                Instant time = open.plusSeconds(bar * 300L);
                candles.add(candle(id++, time, close));
            }
        }
        return List.copyOf(candles);
    }

    private static MarketCandle candle(long id, Instant time, BigDecimal close) {
        return new MarketCandle(id, 1L, "NSE", "RELIANCE-EQ", "2885", null, "FIVE_MINUTE",
                time, close, close.add(new BigDecimal("0.70")), close.subtract(new BigDecimal("0.70")), close,
                1_000L + (id % 13) * 100L, "TEST_FIXTURE", time.plusSeconds(1));
    }
}
