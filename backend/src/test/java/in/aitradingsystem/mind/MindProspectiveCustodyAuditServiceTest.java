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

class MindProspectiveCustodyAuditServiceTest {
    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    private static final Instant START = Instant.parse("2026-11-11T03:45:00Z");
    private static final Instant SIGNAL = START.plusSeconds(20 * 300);
    private static final Instant RECORDED = SIGNAL.plusSeconds(360);
    private static final Instant NOW = Instant.parse("2026-11-11T10:20:00Z");
    private static final UUID COHORT_ID = UUID.randomUUID();
    private static final UUID CATALOGUE = UUID.randomUUID();
    private static final String FINGERPRINT = "b".repeat(64);

    private final MindProspectiveCohortRepository cohorts =
        mock(MindProspectiveCohortRepository.class);
    private final MindProspectiveOutcomeRepository outcomes =
        mock(MindProspectiveOutcomeRepository.class);
    private final MarketDataRepository market = mock(MarketDataRepository.class);
    private final MarketSessionCalendar calendar = mock(MarketSessionCalendar.class);
    private final MindProspectiveOutcomeEvaluator evaluator =
        new MindProspectiveOutcomeEvaluator();

    private MindProspectiveCustodyAuditService service() {
        return new MindProspectiveCustodyAuditService(
            cohorts, outcomes, market, calendar, evaluator,
            Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private MindProspectiveCohortRepository.Cohort cohort() {
        List<MindProspectiveCohortRepository.Member> members = new ArrayList<>();
        for (int i = 1; i <= 20; i++) {
            members.add(new MindProspectiveCohortRepository.Member(
                i, 50000L + i, CATALOGUE, "NSE",
                "STOCK" + i + "-EQ", "token" + i));
        }
        return new MindProspectiveCohortRepository.Cohort(
            COHORT_ID, MindProspectiveCohortRepository.CODE,
            MindProspectiveCohortRepository.PROTOCOL,
            "MA_RECLAIM_V1", CATALOGUE,
            NOW.minusSeconds(86400), NOW.minusSeconds(86400),
            START, 10, List.copyOf(members));
    }

    private static MarketCandle candle(Instant at, BigDecimal high, BigDecimal low) {
        return new MarketCandle(1, 50001L, "NSE", "STOCK1-EQ",
            "token1", CATALOGUE, "FIVE_MINUTE", at,
            new BigDecimal("100"), high, low, new BigDecimal("100"),
            1000, "ANGEL_ONE_SMARTAPI", at.plusSeconds(330));
    }

    private static List<MarketCandle> original() {
        List<MarketCandle> bars = new ArrayList<>();
        for (int i = 0; i < 21; i++) {
            bars.add(candle(START.plusSeconds(i * 300),
                new BigDecimal("101"), new BigDecimal("99.5")));
        }
        return List.copyOf(bars);
    }

    private static List<ExpectedCandleSlot> slots() {
        List<ExpectedCandleSlot> slots = new ArrayList<>();
        for (int i = 0; i < 75; i++) {
            slots.add(new ExpectedCandleSlot(
                START.plusSeconds(i * 300), HistoricalCandleInterval.FIVE_MINUTE));
        }
        return List.copyOf(slots);
    }

    private static MindProspectiveOutcomeRepository.Receipt receipt() {
        String digest = MindSourceSnapshot.fromCandles(original(),
            50001L, "NSE", "STOCK1-EQ", "FIVE_MINUTE",
            SIGNAL, RECORDED).digest();
        return new MindProspectiveOutcomeRepository.Receipt(
            COHORT_ID, 1, SIGNAL, RECORDED, FINGERPRINT, digest,
            new BigDecimal("100"), new BigDecimal("99"),
            new BigDecimal("102"), 50001L, CATALOGUE, "NSE",
            "STOCK1-EQ", "token1");
    }

    private static MarketCandle stopped() {
        return candle(SIGNAL.plusSeconds(300),
            new BigDecimal("103"), new BigDecimal("98"));
    }

    private static MindProspectiveOutcomeRepository.AuditedOutcome saved(
            MindProspectiveOutcomeRepository.Receipt receipt) {
        var o = new MindProspectiveOutcomeEvaluator()
            .evaluate(receipt, List.of(stopped()), slots(), NOW).orElseThrow();
        return new MindProspectiveOutcomeRepository.AuditedOutcome(
            o.entryCandleAt(), o.exitCandleAt(), NOW.minusSeconds(300),
            receipt.candidateFingerprint(), receipt.originalSourceDigest(),
            o.outcomeSourceDigest(), o.rawEntry(), o.rawExit(),
            o.grossReturnPercent(), o.illustrativeNetReturnPercent(),
            o.exitReason(), o.sourceBars());
    }

    private void withCohortAndCandidate(MindProspectiveOutcomeRepository.Receipt r) {
        when(cohorts.current()).thenReturn(Optional.of(cohort()));
        when(outcomes.candidatePage(COHORT_ID, 0, 100)).thenReturn(List.of(r));
        when(outcomes.recordedOutcome(r)).thenReturn(Optional.of(saved(r)));
    }

    private void withCalendar() {
        when(calendar.session(any(LocalDate.class))).thenAnswer(inv -> {
            LocalDate day = inv.getArgument(0);
            return new MarketSession(day,
                ZonedDateTime.of(day, LocalTime.of(9, 15), IST),
                ZonedDateTime.of(day, LocalTime.of(15, 30), IST), true);
        });
        when(calendar.expectedSlots(any(LocalDate.class),
            eq(HistoricalCandleInterval.FIVE_MINUTE))).thenReturn(slots());
    }

    @Test
    void absentCohortCannotBeRepresentedAsVerifiedResearch() {
        when(cohorts.current()).thenReturn(Optional.empty());
        assertThrows(IllegalStateException.class, () -> service().audit(0));
        verifyNoInteractions(outcomes, market, calendar);
    }

    @Test
    void pageMustBeBoundedAndAlignedWithoutReadingBrokerData() {
        for (int bad : new int[]{-1, 1, 15001, 15100}) {
            assertThrows(IllegalArgumentException.class, () -> service().audit(bad));
        }
        verifyNoInteractions(cohorts, outcomes, market, calendar);
    }

    @Test
    void noSavedOutcomeRemainsPendingAndReadsNoMarketCandles() {
        var r = receipt();
        when(cohorts.current()).thenReturn(Optional.of(cohort()));
        when(outcomes.candidatePage(COHORT_ID, 0, 100)).thenReturn(List.of(r));
        when(outcomes.recordedOutcome(r)).thenReturn(Optional.empty());
        var report = service().audit(0);
        assertEquals(1, report.awaitingOutcome());
        assertEquals(0, report.internallyReproduced());
        assertEquals("INCOMPLETE_RESEARCH_EVIDENCE", report.status());
        assertFalse(report.executionEnabled());
        verifyNoInteractions(market, calendar);
    }

    @Test
    void reproducesOriginalAndOutcomeDigestsButNeverClaimsExchangeProof() {
        var r = receipt();
        withCohortAndCandidate(r);
        withCalendar();
        when(market.recentCandlesAsOf(50001L, "FIVE_MINUTE", SIGNAL, 21))
            .thenReturn(original());
        when(market.backtestCandles(50001L, "FIVE_MINUTE",
            SIGNAL.plusSeconds(300), SIGNAL.plusSeconds(600), 75))
            .thenReturn(List.of(stopped()));
        var report = service().audit(0);
        assertEquals(1, report.internallyReproduced());
        assertEquals(0, report.originalInputUnavailableOrRevised());
        assertEquals(0, report.outcomeInputUnavailableOrRevised());
        assertEquals("INTERNALLY_REPRODUCED_RESEARCH_ONLY", report.status());
        assertEquals("ABSTAIN", report.decisionPosture());
        assertFalse(report.independentlyVerified());
        assertFalse(report.outcomeValidated());
        assertFalse(report.paperOrderSubmitted());
        assertFalse(report.executionEnabled());
    }

    @Test
    void changedOriginalBarInvalidatesInternalKnownAtClaim() {
        var r = receipt();
        withCohortAndCandidate(r);
        var revised = new ArrayList<>(original());
        revised.set(10, candle(START.plusSeconds(10 * 300),
            new BigDecimal("105"), new BigDecimal("99.5")));
        when(market.recentCandlesAsOf(50001L, "FIVE_MINUTE", SIGNAL, 21))
            .thenReturn(revised);
        var report = service().audit(0);
        assertEquals(1, report.originalInputUnavailableOrRevised());
        assertEquals(0, report.internallyReproduced());
        assertEquals("INTERNAL_EVIDENCE_NONREPRODUCIBLE", report.status());
        verify(market, never()).backtestCandles(anyLong(), anyString(),
            any(), any(), anyInt());
    }

    @Test
    void changedOutcomeBarCannotRetainPriorImmutableReturnAsReproduced() {
        var r = receipt();
        withCohortAndCandidate(r);
        withCalendar();
        when(market.recentCandlesAsOf(50001L, "FIVE_MINUTE", SIGNAL, 21))
            .thenReturn(original());
        when(market.backtestCandles(50001L, "FIVE_MINUTE",
            SIGNAL.plusSeconds(300), SIGNAL.plusSeconds(600), 75))
            .thenReturn(List.of(candle(SIGNAL.plusSeconds(300),
                new BigDecimal("104"), new BigDecimal("98"))));
        var report = service().audit(0);
        assertEquals(1, report.outcomeInputUnavailableOrRevised());
        assertEquals(0, report.internallyReproduced());
        assertEquals("INTERNAL_EVIDENCE_NONREPRODUCIBLE", report.status());
    }
}
