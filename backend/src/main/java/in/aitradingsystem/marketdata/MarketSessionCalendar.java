package in.aitradingsystem.marketdata;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

public interface MarketSessionCalendar {
    boolean isTradingDay(LocalDate date);
    MarketSession session(LocalDate date);
    boolean isInsideCashSession(Instant instant);

    /**
     * F&O is a distinct exchange segment with a 15:40 IST NORMAL_CLOSE
     * effective August 2026. NEVER reuse the cash/auction 15:30 cutoff.
     * An exchange-specific holiday schedule may further restrict sessions.
     */
    default boolean isInsideFnoSession(Instant instant) {
        if (instant == null) return false;
        var ist = instant.atZone(java.time.ZoneId.of("Asia/Kolkata"));
        var day = ist.getDayOfWeek();
        if (day == java.time.DayOfWeek.SATURDAY ||
                day == java.time.DayOfWeek.SUNDAY) return false;
        var time = ist.toLocalTime();
        return isTradingDay(ist.toLocalDate())
            && !time.isBefore(java.time.LocalTime.of(9, 15))
            && time.isBefore(java.time.LocalTime.of(15, 40));
    }
    Optional<Instant> previousExpectedCandle(Instant candle, HistoricalCandleInterval interval);
    List<ExpectedCandleSlot> expectedSlots(LocalDate date, HistoricalCandleInterval interval);
}
