package in.aitradingsystem.marketdata;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

public interface MarketSessionCalendar {
    boolean isTradingDay(LocalDate date);
    MarketSession session(LocalDate date);
    boolean isInsideCashSession(Instant instant);
    Optional<Instant> previousExpectedCandle(Instant candle, HistoricalCandleInterval interval);
    List<ExpectedCandleSlot> expectedSlots(LocalDate date, HistoricalCandleInterval interval);
}
