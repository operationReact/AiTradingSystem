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
    void official2026MuhuratSundayUsesSpecialConservativeCashSession() {
        LocalDate date = LocalDate.of(2026, 11, 8);
        assertTrue(calendar.isTradingDay(date));
        MarketSession session = calendar.session(date);
        assertTrue(session.tradingDay());
        assertEquals(LocalTime.of(14, 20), session.open().toLocalTime());
        assertEquals(LocalTime.of(15, 15), session.close().toLocalTime());
        assertFalse(calendar.isInsideCashSession(session.open().minusNanos(1).toInstant()));
        assertTrue(calendar.isInsideCashSession(session.open().toInstant()));
        assertTrue(calendar.isInsideCashSession(session.close().minusNanos(1).toInstant()));
        assertFalse(calendar.isInsideCashSession(session.close().toInstant()));
        var slots = calendar.expectedSlots(date, HistoricalCandleInterval.ONE_MINUTE);
        assertEquals(55, slots.size());
        assertEquals(LocalTime.of(14, 20), slots.getFirst().timestamp().atZone(IndianCashMarketCalendar.ZONE).toLocalTime());
        assertEquals(LocalTime.of(15, 14), slots.getLast().timestamp().atZone(IndianCashMarketCalendar.ZONE).toLocalTime());
        verifyNoInteractions(jdbc);
    }

    @Test
    void otherSundayRemainsClosedAndCannotInheritMuhuratHours() {
        LocalDate date = LocalDate.of(2026, 11, 15);
        assertFalse(calendar.isTradingDay(date));
        assertFalse(calendar.isInsideCashSession(ZonedDateTime.of(date, LocalTime.of(14, 30),
                IndianCashMarketCalendar.ZONE).toInstant()));
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
