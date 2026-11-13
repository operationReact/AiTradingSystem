package in.aitradingsystem.canonical;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;

/** Fail-closed quality assessment for one provider/listing/version candle partition. */
public final class CanonicalCandleWindowQuality {
    public static final String SCHEMA_VERSION = "minttick.canonical.window-quality.v1";

    public enum Status { VALIDATED, EMPTY, MIXED_PARTITION, OUT_OF_ORDER, DUPLICATE, GAP, NOT_AVAILABLE, RIGHTS_DENIED }

    public record Assessment(Status status, int observedRows, int eligibleRows, int missingSlots,
                             Instant firstEventAt, Instant lastEventAt) {
        public boolean usable() { return status == Status.VALIDATED; }
    }

    /**
     * Input must already be ordered by exchange timestamp. No sorting, interpolation, session
     * inference, or cross-provider substitution is performed. Caller supplies the expected
     * spacing only for an explicitly continuous exchange-session window.
     */
    public static Assessment assess(List<CanonicalCandleInput.Candle> candles,
                                    Duration expectedSpacing, Instant asOf,
                                    CanonicalCandleInput.Use use) {
        Objects.requireNonNull(candles);
        Objects.requireNonNull(asOf);
        Objects.requireNonNull(use);
        if (expectedSpacing == null || expectedSpacing.isNegative() || expectedSpacing.isZero())
            throw new IllegalArgumentException("expectedSpacing must be positive");
        if (candles.isEmpty()) return new Assessment(Status.EMPTY, 0, 0, 0, null, null);
        var first = Objects.requireNonNull(candles.get(0));
        var previous = first;
        int eligible = 0;
        int missing = 0;
        for (int index = 0; index < candles.size(); index++) {
            var candle = candles.get(index);
            Objects.requireNonNull(candle);
            if (!samePartition(first, candle))
                return result(Status.MIXED_PARTITION, candles, eligible, missing);
            if (!candle.rights().permits(use))
                return result(Status.RIGHTS_DENIED, candles, eligible, missing);
            if (!candle.eligible(use, asOf))
                return result(Status.NOT_AVAILABLE, candles, eligible, missing);
            if (index > 0) {
                long delta = Duration.between(previous.exchangeTimestamp(), candle.exchangeTimestamp()).toNanos();
                if (delta == 0) return result(Status.DUPLICATE, candles, eligible, missing);
                if (delta < 0) return result(Status.OUT_OF_ORDER, candles, eligible, missing);
                long spacing = expectedSpacing.toNanos();
                if (delta != spacing) {
                    if (delta > spacing && delta % spacing == 0) {
                        long slots = delta / spacing - 1;
                        missing = (int) Math.min(Integer.MAX_VALUE, (long) missing + slots);
                    }
                    return result(Status.GAP, candles, eligible, missing);
                }
            }
            eligible++;
            previous = candle;
        }
        return result(Status.VALIDATED, candles, eligible, 0);
    }

    private static boolean samePartition(CanonicalCandleInput.Candle a, CanonicalCandleInput.Candle b) {
        return a.instrumentId().equals(b.instrumentId())
                && a.listingEpisodeId().equals(b.listingEpisodeId())
                && a.exchange().equals(b.exchange())
                && a.interval().equals(b.interval())
                && a.provider().equals(b.provider())
                && a.datasetVersion().equals(b.datasetVersion());
    }

    private static Assessment result(Status status, List<CanonicalCandleInput.Candle> candles,
                                     int eligible, int missing) {
        return new Assessment(status, candles.size(), eligible, missing,
                candles.get(0).exchangeTimestamp(), candles.get(candles.size() - 1).exchangeTimestamp());
    }

    private CanonicalCandleWindowQuality() { }
}
