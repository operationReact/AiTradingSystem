package in.aitradingsystem.marketdata;

import java.time.Instant;

public record ExpectedCandleSlot(Instant timestamp, HistoricalCandleInterval interval) {}
