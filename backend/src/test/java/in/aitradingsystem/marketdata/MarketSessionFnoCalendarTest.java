package in.aitradingsystem.marketdata;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

import java.time.Instant;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;

/**
 * NSE_FO NORMAL_CLOSE changed to 15:40 IST in August 2026, while
 * NSE_CASH remains separate. Never apply cash candle-close to options.
 */
class MarketSessionFnoCalendarTest {
    @Test
    void fnoSessionClosesAt1540IstNot1530AndRespectsHolidays() {
        MarketSessionCalendar calendar = mock(
            MarketSessionCalendar.class, CALLS_REAL_METHODS);
        when(calendar.isTradingDay(LocalDate.of(2026,11,17))).thenReturn(true);
        assertThat(calendar.isInsideFnoSession(
            Instant.parse("2026-11-17T03:44:59Z"))).isFalse();
        assertThat(calendar.isInsideFnoSession(
            Instant.parse("2026-11-17T03:45:00Z"))).isTrue();
        assertThat(calendar.isInsideFnoSession(
            Instant.parse("2026-11-17T10:00:00Z"))).isTrue();
        assertThat(calendar.isInsideFnoSession(
            Instant.parse("2026-11-17T10:09:59Z"))).isTrue();
        assertThat(calendar.isInsideFnoSession(
            Instant.parse("2026-11-17T10:10:00Z"))).isFalse();
        assertThat(calendar.isInsideFnoSession(
            Instant.parse("2026-11-15T05:00:00Z"))).isFalse();
        when(calendar.isTradingDay(LocalDate.of(2026,11,18))).thenReturn(false);
        assertThat(calendar.isInsideFnoSession(
            Instant.parse("2026-11-18T05:00:00Z"))).isFalse();
    }
}
