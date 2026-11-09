package in.aitradingsystem.mind;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import in.aitradingsystem.marketdata.ExpectedCandleSlot;
import in.aitradingsystem.marketdata.HistoricalCandleInterval;
import in.aitradingsystem.marketdata.MarketSession;
import in.aitradingsystem.marketdata.MarketSessionCalendar;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class MindProspectiveCohortServiceTest {
    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    private static final Instant REGISTERED = Instant.parse("2026-11-09T16:00:00Z");
    private static final Instant START = Instant.parse("2026-11-10T03:45:00Z");
    private static final UUID VERSION = UUID.randomUUID();
    private static final UUID ID = UUID.randomUUID();
    private final MindProspectiveCohortRepository repository =
        mock(MindProspectiveCohortRepository.class);
    private final MarketSessionCalendar calendar = mock(MarketSessionCalendar.class);

    private static MarketSession session(LocalDate date) {
        boolean trading = date.getDayOfWeek() != DayOfWeek.SATURDAY &&
            date.getDayOfWeek() != DayOfWeek.SUNDAY;
        return new MarketSession(date, ZonedDateTime.of(date,
            LocalTime.of(9, 15), IST),
            ZonedDateTime.of(date, LocalTime.of(15, 30), IST), trading);
    }

    private static List<ExpectedCandleSlot> slots(LocalDate date) {
        if (!session(date).tradingDay()) return List.of();
        Instant start = session(date).open().toInstant();
        return List.of(
            new ExpectedCandleSlot(start, HistoricalCandleInterval.FIVE_MINUTE),
            new ExpectedCandleSlot(start.plusSeconds(300),
                HistoricalCandleInterval.FIVE_MINUTE));
    }

    private void configureCalendar() {
        when(calendar.session(any(LocalDate.class))).thenAnswer(
            call -> session(call.getArgument(0)));
        when(calendar.expectedSlots(any(LocalDate.class),
            eq(HistoricalCandleInterval.FIVE_MINUTE))).thenAnswer(
            call -> slots(call.getArgument(0)));
    }

    private MindProspectiveCohortRepository.Cohort registered() {
        List<MindProspectiveCohortRepository.Member> members = new ArrayList<>();
        for (int i = 1; i <= 20; i++) {
            members.add(new MindProspectiveCohortRepository.Member(
                i, 50000L + i, VERSION, "NSE",
                "EQ" + i + "-EQ", String.valueOf(80000 + i)));
        }
        return new MindProspectiveCohortRepository.Cohort(
            ID, MindProspectiveCohortRepository.CODE,
            MindProspectiveCohortRepository.PROTOCOL, "MA_RECLAIM_V1",
            VERSION, REGISTERED.minusSeconds(3600), REGISTERED,
            START, 10, List.copyOf(members));
    }

    private List<MindProspectiveCohortRepository.Observation> futureObservations(
            boolean syntheticForFirst) {
        List<MindProspectiveCohortRepository.Observation> observations =
            new ArrayList<>();
        LocalDate firstDay = START.atZone(IST).toLocalDate();
        int completed = 0;
        for (int i = 0; i < 30 && completed < 10; i++) {
            LocalDate date = firstDay.plusDays(i);
            if (!session(date).tradingDay()) continue;
            completed++;
            for (var slot : slots(date)) {
                for (int member = 1; member <= 20; member++) {
                    observations.add(new MindProspectiveCohortRepository.Observation(
                        member, slot.timestamp(), slot.timestamp().plusSeconds(360),
                        syntheticForFirst && member == 1 ? "TEST_FIXTURE" :
                            "ANGEL_ONE_SMARTAPI",
                        new BigDecimal("100"), new BigDecimal("101"),
                        new BigDecimal("99"), new BigDecimal("100"), 1000));
                }
            }
        }
        return observations;
    }

    private MindProspectiveCohortService service(Instant now) {
        return new MindProspectiveCohortService(repository, calendar,
            Clock.fixed(now, ZoneOffset.UTC));
    }

    @Test
    void missingCohortCannotBeRetroactivelyPresentedAsProspectivePerformance() {
        when(repository.current()).thenReturn(Optional.empty());
        var status = service(REGISTERED).inspect();
        assertEquals("UNREGISTERED", status.sourceStatus());
        assertEquals(0, status.memberCount());
        assertTrue(status.blockers().contains("COHORT_NOT_REGISTERED"));
        assertEquals("ABSTAIN", status.decisionPosture());
        assertFalse(status.outcomeValidated());
        assertFalse(status.executionEnabled());
        verify(repository, never()).observations(any(), any(), any(), anyInt());
    }

    @Test
    void registrationStartsAtTomorrowSessionAndCannotUseEarlierBars() {
        configureCalendar();
        when(repository.current()).thenReturn(Optional.of(registered()));
        var response = service(REGISTERED).register();

        verify(repository).register(any(UUID.class), eq(REGISTERED), eq(START));
        assertEquals("AWAITING_FUTURE_SESSION", response.sourceStatus());
        assertEquals(20, response.memberCount());
        assertEquals(0, response.completedSessions());
        assertFalse(response.outcomeValidated());
        verify(repository, never()).observations(any(), any(), any(), anyInt());
    }

    @Test
    void tenCompletedSessionsCanReportCoverageButNeverTradingEdge() {
        configureCalendar();
        when(repository.current()).thenReturn(Optional.of(registered()));
        when(repository.observations(eq(ID), eq(START), any(Instant.class), eq(20001)))
            .thenReturn(futureObservations(false));
        var result = service(Instant.parse("2026-12-01T16:00:00Z")).inspect();

        assertEquals("SOURCE_COVERAGE_READY_RESEARCH_ONLY", result.sourceStatus());
        assertEquals(10, result.completedSessions());
        assertEquals(20, result.membersWithSufficientCoverage());
        assertEquals(20, result.memberCount());
        for (var member : result.members()) {
            assertEquals("SOURCE_COVERAGE_RECORDED", member.state());
            assertEquals(new BigDecimal("100.00"), member.coveragePercent());
        }
        assertTrue(result.blockers().contains("PROSPECTIVE_RETURNS_NOT_EVALUATED"));
        assertEquals("ABSTAIN", result.decisionPosture());
        assertFalse(result.outcomeValidated());
        assertFalse(result.paperOrderSubmitted());
        assertFalse(result.executionEnabled());
    }

    @Test
    void syntheticOrLateBarsDoNotPassProspectiveSourceCoverage() {
        configureCalendar();
        when(repository.current()).thenReturn(Optional.of(registered()));
        when(repository.observations(eq(ID), eq(START), any(Instant.class), eq(20001)))
            .thenReturn(futureObservations(true));
        var result = service(Instant.parse("2026-12-01T16:00:00Z")).inspect();
        assertEquals("INSUFFICIENT_COVERAGE", result.sourceStatus());
        assertEquals(19, result.membersWithSufficientCoverage());
        assertEquals("INSUFFICIENT_SOURCE_COVERAGE", result.members().getFirst().state());
        assertEquals(new BigDecimal("0.00"), result.members().getFirst().coveragePercent());
        assertFalse(result.outcomeValidated());
    }

    @Test
    void futureRegisteredAtOrInvalidMembershipNeverExecutesAResearchScan() {
        configureCalendar();
        var original = registered();
        var backdated = new MindProspectiveCohortRepository.Cohort(
            original.id(), original.cohortCode(), original.protocolVersion(),
            original.strategyCode(), original.catalogueVersion(),
            original.catalogueActivatedAt(),
            REGISTERED.plusSeconds(120), START, 10, original.members());
        when(repository.current()).thenReturn(Optional.of(backdated));
        var status = service(REGISTERED).inspect();
        assertEquals("UNREGISTERED", status.sourceStatus());
        assertTrue(status.blockers().contains("COHORT_MEMBERSHIP_INVALID"));
        verify(repository, never()).observations(any(), any(), any(), anyInt());
    }
}
