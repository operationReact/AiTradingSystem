package in.aitradingsystem.research.simulation;

import static org.assertj.core.api.Assertions.assertThat;

import in.aitradingsystem.marketdata.MarketCandle;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class SessionBoundarySamplingTest {
    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");

    @Test
    void blocksNeverCrossTradeDateOrSessionClose() {
        List<MarketCandle> history = fullSessions(LocalDate.of(2026, 1, 5), 2);
        HistoricalBlockSampler.SamplingPool pool = new HistoricalBlockSampler()
                .select(history, history.get(history.size() - 1).candleTime(), 10);

        assertThat(pool.allEligibleBlockCount()).isEqualTo(111);
        assertThat(pool.blocks()).allSatisfy(block -> {
            var start = block.startCandleTime().atZone(IST);
            assertThat(start.toLocalDate()).isEqualTo(block.sourceTradeDate());
            assertThat(start.toLocalTime()).isGreaterThanOrEqualTo(LocalTime.of(9, 20));
            assertThat(start.toLocalTime().plusMinutes(45)).isBefore(LocalTime.of(15, 30));
            assertThat(block.observations()).hasSize(10);
        });
    }

    @Test
    void blocksCannotContainCandlesAfterCutoff() {
        List<MarketCandle> history = fullSessions(LocalDate.of(2026, 1, 5), 2);
        Instant cutoff = LocalDate.of(2026, 1, 6).atTime(10, 0).atZone(IST).toInstant();
        HistoricalBlockSampler.SamplingPool pool = new HistoricalBlockSampler().select(history, cutoff, 4);

        assertThat(pool.blocks()).allSatisfy(block ->
                assertThat(block.startCandleTime().plusSeconds(3 * 300L)).isBeforeOrEqualTo(cutoff));
    }

    private static List<MarketCandle> fullSessions(LocalDate firstDate, int days) {
        List<MarketCandle> candles = new ArrayList<>();
        long id = 1;
        BigDecimal close = new BigDecimal("100");
        for (int day = 0; day < days; day++) {
            Instant open = firstDate.plusDays(day).atTime(9, 15).atZone(IST).toInstant();
            for (int bar = 0; bar < 75; bar++) {
                close = close.add(BigDecimal.valueOf(((bar + day) % 5 - 2) * 0.08d));
                Instant time = open.plusSeconds(bar * 300L);
                candles.add(new MarketCandle(id, 1L, "NSE", "RELIANCE-EQ", "2885", null, "FIVE_MINUTE",
                        time, close, close.add(new BigDecimal("0.5")), close.subtract(new BigDecimal("0.5")), close,
                        1_000L + (id++ % 9) * 100L, "TEST_FIXTURE", time.plusSeconds(1)));
            }
        }
        return List.copyOf(candles);
    }
}
