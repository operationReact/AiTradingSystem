package in.aitradingsystem.mind;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import in.aitradingsystem.marketdata.ExpectedCandleSlot;
import in.aitradingsystem.marketdata.HistoricalCandleInterval;
import in.aitradingsystem.marketdata.MarketCandle;
import in.aitradingsystem.marketdata.MarketDataRepository;
import in.aitradingsystem.marketdata.MarketSession;
import in.aitradingsystem.marketdata.MarketSessionCalendar;
import java.math.BigDecimal;
import java.time.Clock;
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

class MindProspectiveOutcomeServiceTest {
    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    private static final Instant START = Instant.parse("2026-11-11T03:45:00Z");
    private static final Instant SIGNAL = START.plusSeconds(20 * 300);
    private static final Instant RECORDED = SIGNAL.plusSeconds(360);
    private static final Instant NOW = Instant.parse("2026-11-11T10:16:00Z");
    private static final UUID COHORT = UUID.randomUUID();
    private static final UUID CATALOGUE = UUID.randomUUID();

    private final MindProspectiveCohortRepository cohorts = mock(MindProspectiveCohortRepository.class);
    private final MindProspectiveOutcomeRepository outcomes = mock(MindProspectiveOutcomeRepository.class);
    private final MarketDataRepository market = mock(MarketDataRepository.class);
    private final MarketSessionCalendar calendar = mock(MarketSessionCalendar.class);

    private MindProspectiveOutcomeService service(Instant at) {
        return new MindProspectiveOutcomeService(cohorts, outcomes, market,
            calendar, new MindProspectiveOutcomeEvaluator(),
            Clock.fixed(at, ZoneOffset.UTC));
    }

    private MindProspectiveCohortRepository.Cohort cohort() {
        List<MindProspectiveCohortRepository.Member> members = new ArrayList<>();
        for (int i = 1; i <= 20; i++)
            members.add(new MindProspectiveCohortRepository.Member(
                i, 50000L + i, CATALOGUE, "NSE", "STOCK" + i + "-EQ", "token" + i));
        return new MindProspectiveCohortRepository.Cohort(
            COHORT, MindProspectiveCohortRepository.CODE,
            MindProspectiveCohortRepository.PROTOCOL, "MA_RECLAIM_V1",
            CATALOGUE, NOW.minusSeconds(86400), NOW.minusSeconds(12 * 3600),
            START, 10, List.copyOf(members));
    }

    private MindProspectiveOutcomeRepository.Receipt receipt() {
        return new MindProspectiveOutcomeRepository.Receipt(
            COHORT, 1, SIGNAL, RECORDED, "a".repeat(64), "b".repeat(64),
            new BigDecimal("100"), new BigDecimal("99"),
            new BigDecimal("102"), 50001L, CATALOGUE,
            "NSE", "STOCK1-EQ", "token1");
    }

    private void setupCalendar() {
        when(calendar.session(any(LocalDate.class))).thenAnswer(inv -> {
            LocalDate d = inv.getArgument(0);
            return new MarketSession(d,
                ZonedDateTime.of(d, LocalTime.of(9, 15), IST),
                ZonedDateTime.of(d, LocalTime.of(15, 30), IST), true);
        });
        when(calendar.expectedSlots(any(LocalDate.class),
            eq(HistoricalCandleInterval.FIVE_MINUTE))).thenAnswer(inv -> {
            LocalDate d = inv.getArgument(0);
            Instant open = d.atTime(9, 15).atZone(IST).toInstant();
            List<ExpectedCandleSlot> slots = new ArrayList<>();
            for (int i = 0; i < 75; i++) slots.add(new ExpectedCandleSlot(
                open.plusSeconds(i * 300), HistoricalCandleInterval.FIVE_MINUTE));
            return slots;
        });
    }

    @Test
    void absentCohortCannotFabricateProspectiveOutcomes() {
        when(cohorts.current()).thenReturn(Optional.empty());
        assertEquals("UNREGISTERED", service(NOW).inspect().status());
        assertThrows(IllegalStateException.class, () -> service(NOW).reconcile(0));
        verifyNoInteractions(outcomes, market, calendar);
    }

    @Test
    void readsRealFrozenCandidateAndRecordsStopAfterSessionCloseOnly() {
        setupCalendar();
        when(cohorts.current()).thenReturn(Optional.of(cohort()));
        when(outcomes.candidatePage(COHORT, 0, 100)).thenReturn(List.of(receipt()));
        Instant entry = SIGNAL.plusSeconds(300);
        var source = new MarketCandle(1, 50001L, "NSE", "STOCK1-EQ",
            "token1", CATALOGUE, "FIVE_MINUTE", entry,
            new BigDecimal("100"), new BigDecimal("103"),
            new BigDecimal("98"), new BigDecimal("101"), 1000,
            "ANGEL_ONE_SMARTAPI", entry.plusSeconds(330));
        when(market.backtestCandles(50001L, "FIVE_MINUTE", entry,
            Instant.parse("2026-11-11T10:00:00Z"), 75))
            .thenReturn(List.of(source));
        when(outcomes.append(eq(receipt()), any(), eq(NOW))).thenReturn(true);

        var reconciled = service(NOW).reconcile(0);
        assertEquals(1, reconciled.scanned());
        assertEquals(1, reconciled.appended());
        assertEquals(0, reconciled.sourceBlocked());
        assertFalse(reconciled.executionEnabled());
        verify(outcomes).append(eq(receipt()), argThat(outcome ->
            outcome != null && "STOP".equals(outcome.exitReason())), eq(NOW));
    }

    @Test
    void neverReconcilesBeforeSessionClosesAndDataSettles() {
        setupCalendar();
        when(cohorts.current()).thenReturn(Optional.of(cohort()));
        when(outcomes.candidatePage(COHORT, 0, 100)).thenReturn(List.of(receipt()));
        var pending = service(Instant.parse("2026-11-11T10:05:00Z")).reconcile(0);
        assertEquals(1, pending.notMature());
        assertEquals(0, pending.appended());
        verifyNoInteractions(market);
        verify(outcomes, never()).append(any(), any(), any());
    }

    @Test
    void missingMarketDataRemainsBlockedAndDoesNotBecomeReturn() {
        setupCalendar();
        when(cohorts.current()).thenReturn(Optional.of(cohort()));
        when(outcomes.candidatePage(COHORT, 0, 100)).thenReturn(List.of(receipt()));
        var result = service(NOW).reconcile(0);
        assertEquals(1, result.sourceBlocked());
        assertEquals(0, result.appended());
        verify(outcomes, never()).append(any(), any(), any());
    }

    @Test
    void customerInspectIsPersistedCountsOnlyWithoutOrdersOrMarketReads() {
        when(cohorts.current()).thenReturn(Optional.of(cohort()));
        when(outcomes.totals(COHORT))
            .thenReturn(new MindProspectiveOutcomeRepository.Totals(8, 3, 1));
        var result = service(NOW).inspect();
        assertEquals("PARTIAL_RESEARCH_OUTCOMES", result.status());
        assertEquals(8, result.precommittedCandidates());
        assertEquals(3, result.reconciledOutcomes());
        assertEquals("ABSTAIN", result.decisionPosture());
        assertTrue(result.blockers().contains(
            "ENTRY_BAR_OPEN_MAY_PRECEDE_RESEARCH_RECEIPT"));
        assertFalse(result.outcomeValidated());
        assertFalse(result.paperOrderSubmitted());
        assertFalse(result.executionEnabled());
        verifyNoInteractions(market, calendar);
    }

    @Test
    void refusesUnboundedOrUnalignedAdminPagination() {
        assertThrows(IllegalArgumentException.class, () -> service(NOW).reconcile(-1));
        assertThrows(IllegalArgumentException.class, () -> service(NOW).reconcile(1));
        assertThrows(IllegalArgumentException.class, () -> service(NOW).reconcile(15100));
        verifyNoInteractions(cohorts, market, calendar);
    }
}
