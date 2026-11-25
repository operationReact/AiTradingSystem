package in.aitradingsystem.research.simulation;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/** A contiguous, single-session historical return block with point-in-time regime provenance. */
public record HistoricalBlock(
        LocalDate sourceTradeDate,
        Instant startCandleTime,
        List<Observation> observations,
        RegimeSnapshot regimeSnapshot) {

    public HistoricalBlock {
        if (sourceTradeDate == null) throw new IllegalArgumentException("sourceTradeDate is required");
        if (startCandleTime == null) throw new IllegalArgumentException("startCandleTime is required");
        if (observations == null || observations.isEmpty()) throw new IllegalArgumentException("observations are required");
        if (regimeSnapshot == null) throw new IllegalArgumentException("regimeSnapshot is required");
        observations = List.copyOf(observations);
    }

    public record Observation(BigDecimal highFactor, BigDecimal lowFactor, BigDecimal closeFactor) {
        public Observation {
            if (highFactor == null || lowFactor == null || closeFactor == null) {
                throw new IllegalArgumentException("observation factors are required");
            }
            if (highFactor.signum() <= 0 || lowFactor.signum() <= 0 || closeFactor.signum() <= 0) {
                throw new IllegalArgumentException("observation factors must be positive");
            }
        }
    }
}
