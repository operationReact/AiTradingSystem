package in.aitradingsystem.marketdata;

import java.time.*;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/** NSE cash baseline; exchange holidays are supplied by the market_holiday table. */
@Service
public class IndianCashMarketCalendar implements MarketSessionCalendar {
    public static final ZoneId ZONE = ZoneId.of("Asia/Kolkata");
    private static final LocalTime OPEN = LocalTime.of(9, 15);
    private static final LocalTime CLOSE = LocalTime.of(15, 30);
    private final JdbcTemplate jdbc;

    public IndianCashMarketCalendar(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public boolean isTradingDay(LocalDate date) {
        if (date == null) throw new IllegalArgumentException("date is required");
        if (date.getDayOfWeek() == DayOfWeek.SATURDAY || date.getDayOfWeek() == DayOfWeek.SUNDAY) return false;
        return jdbc.queryForObject("SELECT NOT EXISTS (SELECT 1 FROM market_holiday WHERE trade_date = ? AND market = 'NSE_CASH')",
                Boolean.class, date);
    }

    @Override
    public MarketSession session(LocalDate date) {
        boolean trading = isTradingDay(date);
        return new MarketSession(date, ZonedDateTime.of(date, OPEN, ZONE), ZonedDateTime.of(date, CLOSE, ZONE), trading);
    }

    @Override
    public boolean isInsideCashSession(Instant instant) {
        if (instant == null) return false;
        ZonedDateTime local = instant.atZone(ZONE);
        MarketSession s = session(local.toLocalDate());
        return s.tradingDay() && !instant.isBefore(s.open().toInstant()) && instant.isBefore(s.close().toInstant());
    }

    @Override
    public List<ExpectedCandleSlot> expectedSlots(LocalDate date, HistoricalCandleInterval interval) {
        if (interval == null) throw new IllegalArgumentException("interval is required");
        MarketSession s = session(date);
        if (!s.tradingDay()) return List.of();
        Duration step = duration(interval);
        if (interval == HistoricalCandleInterval.ONE_DAY) {
            return List.of(new ExpectedCandleSlot(s.open().toInstant(), interval));
        }
        List<ExpectedCandleSlot> slots = new ArrayList<>();
        for (Instant t = s.open().toInstant(); t.isBefore(s.close().toInstant()); t = t.plus(step)) {
            slots.add(new ExpectedCandleSlot(t, interval));
        }
        return List.copyOf(slots);
    }

    @Override
    public Optional<Instant> previousExpectedCandle(Instant candle, HistoricalCandleInterval interval) {
        if (candle == null || interval == null) return Optional.empty();
        LocalDate date = candle.atZone(ZONE).toLocalDate();
        // Bounded lookback avoids an infinite scan when a holiday calendar is misconfigured.
        for (int offset = 0; offset <= 14; offset++) {
            LocalDate candidate = date.minusDays(offset);
            List<ExpectedCandleSlot> slots = expectedSlots(candidate, interval);
            for (int i = slots.size() - 1; i >= 0; i--) {
                Instant timestamp = slots.get(i).timestamp();
                if (timestamp.isBefore(candle)) return Optional.of(timestamp);
            }
        }
        return Optional.empty();
    }

    private static Duration duration(HistoricalCandleInterval interval) {
        return switch (interval) {
            case ONE_MINUTE -> Duration.ofMinutes(1);
            case THREE_MINUTE -> Duration.ofMinutes(3);
            case FIVE_MINUTE -> Duration.ofMinutes(5);
            case TEN_MINUTE -> Duration.ofMinutes(10);
            case FIFTEEN_MINUTE -> Duration.ofMinutes(15);
            case THIRTY_MINUTE -> Duration.ofMinutes(30);
            case ONE_HOUR -> Duration.ofHours(1);
            case ONE_DAY -> Duration.ofDays(1);
        };
    }
}
