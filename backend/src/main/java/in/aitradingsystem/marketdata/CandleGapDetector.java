package in.aitradingsystem.marketdata;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/** Detects missing candle intervals without embedding exchange-holiday assumptions. */
public final class CandleGapDetector {

    public record Gap(Instant expectedAt, Instant nextObservedAt, long missingIntervals) {}

    public List<Gap> detect(List<Instant> observedAt, Duration interval) {
        Objects.requireNonNull(observedAt, "observedAt");
        Objects.requireNonNull(interval, "interval");
        if (interval.isZero() || interval.isNegative()) {
            throw new IllegalArgumentException("interval must be positive");
        }

        List<Instant> ordered = observedAt.stream()
                .filter(Objects::nonNull)
                .distinct()
                .sorted(Comparator.naturalOrder())
                .toList();
        List<Gap> gaps = new ArrayList<>();
        long intervalNanos = interval.toNanos();

        for (int i = 1; i < ordered.size(); i++) {
            Instant previous = ordered.get(i - 1);
            Instant current = ordered.get(i);
            long deltaNanos = Duration.between(previous, current).toNanos();
            if (deltaNanos > intervalNanos) {
                long missing = (deltaNanos - 1) / intervalNanos;
                gaps.add(new Gap(previous.plus(interval), current, missing));
            }
        }
        return List.copyOf(gaps);
    }
}
