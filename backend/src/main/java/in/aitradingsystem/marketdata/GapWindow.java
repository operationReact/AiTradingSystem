package in.aitradingsystem.marketdata;

import java.time.Instant;

public record GapWindow(Instant from, Instant toExclusive, int missingCandles) {
    public GapWindow {
        if (from == null || toExclusive == null || !toExclusive.isAfter(from)) {
            throw new IllegalArgumentException("Gap window must have a positive duration");
        }
        if (missingCandles < 1) throw new IllegalArgumentException("missingCandles must be positive");
    }
}
