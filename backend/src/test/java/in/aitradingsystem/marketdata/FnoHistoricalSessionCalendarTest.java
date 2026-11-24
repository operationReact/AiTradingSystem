package in.aitradingsystem.marketdata;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import java.sql.Date;
import java.time.*;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

class FnoHistoricalSessionCalendarTest {
    private static final Instant NOW=Instant.parse("2026-11-24T08:00:00Z");
    private static final Instant AS_OF=Instant.parse("2026-11-23T08:00:00Z");
    private static final LocalDate DAY=LocalDate.of(2026,11,20);
    private static final OffsetDateTime CUTOFF=AS_OF.atOffset(ZoneOffset.UTC);
    private static final String PROVIDER="UPSTOX_ANALYTICS";
    private static final String DOMAIN=FnoHistoricalSessionCalendar.DOMAIN;
    private final JdbcTemplate db=mock(JdbcTemplate.class);
    private final GoldenSourceRightsRepository rights=mock(GoldenSourceRightsRepository.class);
    private final FnoHistoricalSessionCalendar calendar=new FnoHistoricalSessionCalendar(
        db,rights,Clock.fixed(NOW,ZoneOffset.UTC));

    private void rows(List<FnoHistoricalSessionCalendar.Attested> rows) {
        when(db.query(anyString(),
            org.mockito.ArgumentMatchers.<RowMapper<FnoHistoricalSessionCalendar.Attested>>any(),
            eq(DOMAIN),eq(Date.valueOf(DAY)),eq(CUTOFF),eq(CUTOFF),eq(CUTOFF)))
            .thenReturn(rows);
    }
    private FnoHistoricalSessionCalendar.Attested trading() {
        return new FnoHistoricalSessionCalendar.Attested(PROVIDER,"TRADING",
            DAY.atTime(15,40).atOffset(ZoneOffset.ofHoursMinutes(5,30)),"a".repeat(64));
    }
    private void grant() {
        when(rights.isPermitted(eq(PROVIDER),eq(DOMAIN),any(),any())).thenReturn(true);
    }
    @Test void exactHistoricalAndCurrentRightsAreRequired() {
        grant();
        rows(List.of(trading()));
        assertTrue(calendar.isTradingDay(DAY,AS_OF));
        for(var use:List.of(GoldenSourceRightsRepository.Use.RAW_RETENTION,
                            GoldenSourceRightsRepository.Use.MODEL_INFERENCE)) {
            verify(rights).isPermitted(PROVIDER,DOMAIN,use,AS_OF);
            verify(rights).isPermitted(PROVIDER,DOMAIN,use,NOW);
        }
        when(rights.isPermitted(PROVIDER,DOMAIN,
            GoldenSourceRightsRepository.Use.MODEL_INFERENCE,NOW)).thenReturn(false);
        assertThrows(IllegalStateException.class,()->calendar.isTradingDay(DAY,AS_OF));
    }
    @Test void onlyVerifiedClosedIsHolidayAndNoEvidenceIsUnknown() {
        grant();
        rows(List.of(new FnoHistoricalSessionCalendar.Attested(PROVIDER,"CLOSED",null,"b".repeat(64))));
        assertFalse(calendar.isTradingDay(DAY,AS_OF));
        rows(List.of());
        assertThrows(IllegalStateException.class,()->calendar.isTradingDay(DAY,AS_OF));
        rows(List.of(trading(),trading()));
        assertThrows(IllegalStateException.class,()->calendar.isTradingDay(DAY,AS_OF));
    }
    @Test void futureDecisionCannotQueryEvidence() {
        assertThrows(IllegalStateException.class,()->calendar.isTradingDay(DAY,NOW.plusSeconds(1)));
        verifyNoInteractions(db,rights);
    }
}
