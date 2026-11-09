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
import in.aitradingsystem.scanner.MovingAverageReclaimStrategy;
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

class MindProspectiveSignalServiceTest {
    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    private static final Instant NOW = Instant.parse("2026-11-10T05:31:00Z");
    private static final Instant START = Instant.parse("2026-11-10T03:45:00Z");
    private static final Instant SIGNAL = START.plusSeconds(20 * 300);
    private static final UUID COHORT_ID = UUID.randomUUID();
    private static final UUID CATALOGUE = UUID.randomUUID();
    private final MindProspectiveCohortRepository cohorts = mock(MindProspectiveCohortRepository.class);
    private final MindProspectiveSignalRepository receipts = mock(MindProspectiveSignalRepository.class);
    private final MarketDataRepository market = mock(MarketDataRepository.class);
    private final MarketSessionCalendar calendar = mock(MarketSessionCalendar.class);

    private MindProspectiveSignalService service(Instant now) {
        return new MindProspectiveSignalService(
            cohorts, receipts, market, calendar,
            new MovingAverageReclaimStrategy(), Clock.fixed(now, ZoneOffset.UTC));
    }

    private void setupCalendar() {
        when(calendar.session(any(LocalDate.class))).thenAnswer(inv -> {
            LocalDate date = inv.getArgument(0);
            boolean trading = date.getDayOfWeek().getValue() < 6;
            return new MarketSession(date,
                ZonedDateTime.of(date, LocalTime.of(9, 15), IST),
                ZonedDateTime.of(date, LocalTime.of(15, 30), IST), trading);
        });
        when(calendar.expectedSlots(any(LocalDate.class),
                eq(HistoricalCandleInterval.FIVE_MINUTE))).thenAnswer(inv -> {
            LocalDate date = inv.getArgument(0);
            Instant start = date.atTime(9, 15).atZone(IST).toInstant();
            List<ExpectedCandleSlot> slots = new ArrayList<>();
            for (int i = 0; i < 75; i++) {
                slots.add(new ExpectedCandleSlot(
                    start.plusSeconds(i * 300), HistoricalCandleInterval.FIVE_MINUTE));
            }
            return slots;
        });
    }

    private MindProspectiveCohortRepository.Cohort cohort() {
        List<MindProspectiveCohortRepository.Member> members = new ArrayList<>();
        for (int slot = 1; slot <= 20; slot++) {
            members.add(new MindProspectiveCohortRepository.Member(
                slot, 50000L + slot, CATALOGUE, "NSE",
                "STOCK" + slot + "-EQ", "token" + slot));
        }
        return new MindProspectiveCohortRepository.Cohort(
            COHORT_ID, MindProspectiveCohortRepository.CODE,
            MindProspectiveCohortRepository.PROTOCOL, "MA_RECLAIM_V1",
            CATALOGUE, NOW.minusSeconds(20 * 3600), NOW.minusSeconds(12 * 3600),
            START, 10, List.copyOf(members));
    }

    private List<MarketCandle> bars(String source, Instant fetchedOffset) {
        List<MarketCandle> result = new ArrayList<>();
        for (int i = 0; i < 21; i++) {
            Instant at = START.plusSeconds(i * 300);
            result.add(new MarketCandle(
                i + 1, 50001L, "NSE", "STOCK1-EQ", "token1",
                CATALOGUE, "FIVE_MINUTE", at,
                new BigDecimal("100"), new BigDecimal("101"),
                new BigDecimal("99"), new BigDecimal("100"),
                1000, source, at.plusSeconds(330)));
        }
        return List.copyOf(result);
    }

    @Test
    void noCohortCannotBeTurnedIntoBackdatedResearch() {
        when(cohorts.current()).thenReturn(Optional.empty());
        var exception = assertThrows(IllegalStateException.class,
            () -> service(NOW).capture());
        assertEquals("COHORT_NOT_REGISTERED", exception.getMessage());
        verifyNoInteractions(receipts, market);
        var status = service(NOW).inspect();
        assertEquals("UNREGISTERED", status.status());
        assertFalse(status.executionEnabled());
    }

    @Test
    void onlyCurrentClosedBarCanProduceImmutableNoSignalReceipt() {
        setupCalendar();
        when(cohorts.current()).thenReturn(Optional.of(cohort()));
        when(market.recentCandlesAsOf(50001L, "FIVE_MINUTE", SIGNAL, 21))
            .thenReturn(bars("ANGEL_ONE_SMARTAPI", NOW));
        when(receipts.record(eq(COHORT_ID), eq(1), eq(SIGNAL),
                eq(NOW), any(MindSourceSnapshot.class), isNull(), isNull()))
            .thenReturn(true);
        var captured = service(NOW).capture();
        assertEquals(SIGNAL, captured.signalCandleAt());
        assertEquals(1, captured.written());
        assertEquals(0, captured.candidateSignals());
        assertEquals(19, captured.sourceBlocked());
        assertEquals("ABSTAIN", captured.posture());
        assertFalse(captured.executionEnabled());
        verify(receipts, times(1)).record(eq(COHORT_ID), eq(1), eq(SIGNAL),
            eq(NOW), any(MindSourceSnapshot.class), isNull(), isNull());
    }

    @Test
    void partialOrSyntheticSourceCannotBeRecordedAsFutureResearch() {
        setupCalendar();
        when(cohorts.current()).thenReturn(Optional.of(cohort()));
        when(market.recentCandlesAsOf(50001L, "FIVE_MINUTE", SIGNAL, 21))
            .thenReturn(bars("TEST_FIXTURE", NOW));
        var captured = service(NOW).capture();
        assertEquals(0, captured.written());
        assertEquals(20, captured.sourceBlocked());
        verifyNoInteractions(receipts);
    }

    @Test
    void tooEarlyToHaveTwentyOneClosedBarsFailsBeforeAnyDataRead() {
        setupCalendar();
        when(cohorts.current()).thenReturn(Optional.of(cohort()));
        var beforeSignal = Instant.parse("2026-11-10T05:26:00Z");
        assertThrows(IllegalStateException.class,
            () -> service(beforeSignal).capture());
        verifyNoInteractions(market, receipts);
    }

    @Test
    void prospectiveReceiptCountsNeverGrantPerformanceOrOrderAuthority() {
        when(cohorts.current()).thenReturn(Optional.of(cohort()));
        when(receipts.counts(COHORT_ID)).thenReturn(List.of(
            new MindProspectiveSignalRepository.ReceiptCount(1, 6, 2),
            new MindProspectiveSignalRepository.ReceiptCount(2, 6, 0)));
        var status = service(NOW).inspect();
        assertEquals("RESEARCH_RECEIPTS_RECORDED", status.status());
        assertEquals(12, status.recordedWindows());
        assertEquals(2, status.researchCandidates());
        assertEquals(20, status.memberCount());
        assertEquals("ABSTAIN", status.decisionPosture());
        assertTrue(status.blockers().contains("PROSPECTIVE_OUTCOMES_NOT_RECONCILED"));
        assertFalse(status.outcomeValidated());
        assertFalse(status.paperOrderSubmitted());
        assertFalse(status.executionEnabled());
        verifyNoInteractions(market, calendar);
    }
}
