package in.aitradingsystem.marketdata;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class CandleGapDetectorTest {

    private final CandleGapDetector detector = new CandleGapDetector();
    private static final Instant T0 = Instant.parse("2026-10-29T03:45:00Z");

    @Test
    void reportsMissingIntervalsBetweenObservedCandles() {
        var gaps = detector.detect(List.of(T0, T0.plusSeconds(60), T0.plusSeconds(240)), Duration.ofMinutes(1));

        assertThat(gaps).containsExactly(new CandleGapDetector.Gap(
                T0.plusSeconds(120), T0.plusSeconds(240), 2));
    }

    @Test
    void normalizesUnorderedDuplicateObservations() {
        var gaps = detector.detect(List.of(T0.plusSeconds(60), T0, T0.plusSeconds(60)), Duration.ofMinutes(1));

        assertThat(gaps).isEmpty();
    }

    @Test
    void rejectsNonPositiveInterval() {
        assertThatThrownBy(() -> detector.detect(List.of(T0), Duration.ZERO))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
