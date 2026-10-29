package in.aitradingsystem.research.simulation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import in.aitradingsystem.marketdata.MarketCandle;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class RegimeClassifierTest {
    private final RegimeClassifier classifier = new RegimeClassifier();

    @Test
    void classifiesHighVolatilityAndLiquidityFromCompletedHistory() {
        List<MarketCandle> history = history(60);
        RegimeSnapshot snapshot = classifier.classify(history, history.get(59).candleTime());

        assertThat(snapshot.volatilityRegime()).isEqualTo(MarketRegime.HIGH_VOL);
        assertThat(snapshot.volumeRegime()).isEqualTo(LiquidityRegime.HIGH);
        assertThat(snapshot.volatilityMetric()).isGreaterThan(snapshot.upperVolatilityThreshold());
        assertThat(snapshot.volumeMetric()).isGreaterThan(1.20d);
        assertThat(snapshot.methodologyVersion()).isEqualTo(RegimeClassifier.METHODOLOGY);
    }

    @Test
    void ignoresFutureCandlesBeyondDataCutoff() {
        List<MarketCandle> history = new ArrayList<>(history(60));
        Instant cutoff = history.get(59).candleTime();
        RegimeSnapshot before = classifier.classify(history, cutoff);
        history.add(candle(61, cutoff.plus(1, ChronoUnit.DAYS), new BigDecimal("99999"), 99999999L));
        RegimeSnapshot after = classifier.classify(history, cutoff);

        assertThat(after).isEqualTo(before);
    }

    @Test
    void rejectsInsufficientHistory() {
        assertThatThrownBy(() -> classifier.classify(history(10), Instant.parse("2026-02-01T00:00:00Z")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("insufficient completed candles");
    }

    private static List<MarketCandle> history(int count) {
        List<MarketCandle> candles = new ArrayList<>();
        Instant start = Instant.parse("2026-01-01T03:45:00Z");
        BigDecimal close = new BigDecimal("1000");
        for (int i = 0; i < count; i++) {
            if (i > 0) {
                double pct = i < 40 ? (i % 2 == 0 ? 0.001 : -0.001) : (i % 2 == 0 ? 0.03 : -0.03);
                close = close.multiply(BigDecimal.valueOf(1.0d + pct));
            }
            long volume = i < 40 ? 1000L : 4000L;
            candles.add(candle(i + 1L, start.plus(i, ChronoUnit.DAYS), close, volume));
        }
        return List.copyOf(candles);
    }

    private static MarketCandle candle(long id, Instant at, BigDecimal close, long volume) {
        return new MarketCandle(id, 7L, "NSE", "TEST", "7", UUID.randomUUID(), "ONE_DAY", at,
                close, close.multiply(new BigDecimal("1.01")), close.multiply(new BigDecimal("0.99")), close,
                volume, "TEST", at);
    }
}
