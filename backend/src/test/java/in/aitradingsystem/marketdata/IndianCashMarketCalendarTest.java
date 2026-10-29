package in.aitradingsystem.marketdata;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.time.*;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

class IndianCashMarketCalendarTest {
    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final IndianCashMarketCalendar calendar = new IndianCashMarketCalendar(jdbc);

    @Test
    void weekendsAreClosedWithoutDatabaseLookup() {
        assertFalse(calendar.isTradingDay(LocalDate.of(2026, 10, 31)));
        verifyNoInteractions(jdbc);
    }

    @Test
    void holidaysOverrideWeekdays() {
        when(jdbc.queryForObject(anyString(), eq(Boolean.class), any(LocalDate.class))).thenReturn(false);
        assertFalse(calendar.isTradingDay(LocalDate.of(2026, 10, 29)));
    }

    @Test
    void sessionUsesKolkataAndHalfOpenBoundary() {
        when(jdbc.queryForObject(anyString(), eq(Boolean.class), any(LocalDate.class))).thenReturn(true);
        LocalDate date = LocalDate.of(2026, 10, 29);
        MarketSession session = calendar.session(date);
        assertEquals(ZoneId.of("Asia/Kolkata"), session.open().getZone());
        assertEquals(LocalTime.of(9, 15), session.open().toLocalTime());
        assertTrue(calendar.isInsideCashSession(session.open().toInstant()));
        assertFalse(calendar.isInsideCashSession(session.close().toInstant()));
    }

    @Test
    void oneMinuteSlotsExcludeClosingBoundary() {
        when(jdbc.queryForObject(anyString(), eq(Boolean.class), any(LocalDate.class))).thenReturn(true);
        var slots = calendar.expectedSlots(LocalDate.of(2026, 10, 29), HistoricalCandleInterval.ONE_MINUTE);
        assertEquals(375, slots.size());
        assertEquals(LocalTime.of(9, 15), slots.get(0).timestamp().atZone(IndianCashMarketCalendar.ZONE).toLocalTime());
        assertEquals(LocalTime.of(15, 29), slots.get(374).timestamp().atZone(IndianCashMarketCalendar.ZONE).toLocalTime());
    }
}
