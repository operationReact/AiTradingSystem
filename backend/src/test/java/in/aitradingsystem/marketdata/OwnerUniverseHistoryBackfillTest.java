package in.aitradingsystem.marketdata;

import in.aitradingsystem.provider.angelone.AppAngelOneMarketFeedService;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class OwnerUniverseHistoryBackfillTest {
    private static final Instant NOW = Instant.parse("2026-11-10T08:14:00Z");

    @Test
    void coverageWindowUsesFourteenCompletedCalendarDaysInIst() {
        var repository = mock(UniverseCandleCoverageRepository.class);
        var coverage = new UniverseCandleCoverageService(repository, Clock.fixed(NOW, ZoneOffset.UTC));
        var window = coverage.window();

        assertThat(window.fromDate()).isEqualTo(LocalDate.of(2026, 10, 27));
        assertThat(window.toDate()).isEqualTo(LocalDate.of(2026, 11, 9));
        assertThat(window.fromInclusive()).isEqualTo(Instant.parse("2026-10-26T18:30:00Z"));
        assertThat(window.toExclusive()).isEqualTo(Instant.parse("2026-11-09T18:30:00Z"));

        when(repository.nextDue(window.fromInclusive(), window.toExclusive(),
                NOW.minus(Duration.ofHours(18)))).thenReturn(Optional.empty());
        assertThat(coverage.nextDue()).isEmpty();
        verify(repository).nextDue(window.fromInclusive(), window.toExclusive(),
                NOW.minus(Duration.ofHours(18)));
    }

    @Test
    void oneCycleUsesExistingCheckpointedFiveMinuteBackfill() {
        var repo = mock(UniverseCandleCoverageRepository.class);
        var service = new UniverseCandleCoverageService(repo, Clock.fixed(NOW, ZoneOffset.UTC));
        var history = mock(HistoricalCandleService.class);
        var feed = mock(AppAngelOneMarketFeedService.class);
        when(feed.available()).thenReturn(true);
        var window = service.window();
        when(repo.nextDue(window.fromInclusive(), window.toExclusive(),
                NOW.minus(Duration.ofHours(18)))).thenReturn(Optional.of(42L));
        var run = mock(CandleBackfillRun.class);
        when(run.status()).thenReturn("SUCCEEDED");
        when(run.id()).thenReturn(UUID.randomUUID());
        when(history.startWithProvider(eq(42L), eq(HistoricalCandleInterval.FIVE_MINUTE),
                eq(LocalDate.of(2026, 10, 27)), eq(LocalDate.of(2026, 11, 9)),
                any(MarketDataProvider.class)))
                .thenReturn(run);

        new OwnerUniverseHistoryBackfill(service, history,
                feed, new HistoricalCandleParser()).collectOne();

        verify(history, times(1)).startWithProvider(eq(42L), eq(HistoricalCandleInterval.FIVE_MINUTE),
                eq(LocalDate.of(2026, 10, 27)), eq(LocalDate.of(2026, 11, 9)),
                any(MarketDataProvider.class));
        verifyNoMoreInteractions(history);
    }

    @Test
    void noDueInstrumentNeverCallsProvider() {
        var repo = mock(UniverseCandleCoverageRepository.class);
        var service = new UniverseCandleCoverageService(repo, Clock.fixed(NOW, ZoneOffset.UTC));
        var history = mock(HistoricalCandleService.class);
        var feed = mock(AppAngelOneMarketFeedService.class);
        when(feed.available()).thenReturn(true);

        new OwnerUniverseHistoryBackfill(service, history,
                feed, new HistoricalCandleParser()).collectOne();

        verifyNoInteractions(history);
    }

    @Test
    void providerFailureDoesNotKillFutureSchedulerCycles() {
        var repo = mock(UniverseCandleCoverageRepository.class);
        var service = new UniverseCandleCoverageService(repo, Clock.fixed(NOW, ZoneOffset.UTC));
        var history = mock(HistoricalCandleService.class);
        var feed = mock(AppAngelOneMarketFeedService.class);
        when(feed.available()).thenReturn(true);
        var w = service.window();
        when(repo.nextDue(w.fromInclusive(), w.toExclusive(),
                NOW.minus(Duration.ofHours(18)))).thenReturn(Optional.of(42L));
        when(history.startWithProvider(anyLong(), any(), any(), any(), any()))
                .thenThrow(new IllegalStateException("provider not configured"));

        assertThatCode(() -> new OwnerUniverseHistoryBackfill(service, history,
                feed, new HistoricalCandleParser()).collectOne())
                .doesNotThrowAnyException();
        verify(history, times(1)).startWithProvider(anyLong(), any(), any(), any(), any());
    }

    @Test
    void unavailableSsmFeedNeverCreatesFailedRuns() {
        var repo = mock(UniverseCandleCoverageRepository.class);
        var service = new UniverseCandleCoverageService(repo, Clock.fixed(NOW, ZoneOffset.UTC));
        var history = mock(HistoricalCandleService.class);
        var feed = mock(AppAngelOneMarketFeedService.class);

        new OwnerUniverseHistoryBackfill(service, history,
                feed, new HistoricalCandleParser()).collectOne();

        verify(feed).available();
        verifyNoInteractions(repo, history);
    }

    @Test
    void providerFailurePausesEntireUniverseUntilBackoffExpires() {
        var coverage = mock(UniverseCandleCoverageService.class);
        var history = mock(HistoricalCandleService.class);
        var feed = mock(AppAngelOneMarketFeedService.class);
        var clock = new ManualClock(NOW);
        when(feed.available()).thenReturn(true);
        when(coverage.nextDue()).thenReturn(Optional.of(42L));
        when(coverage.window()).thenReturn(new UniverseCandleCoverageService.Window(
                LocalDate.of(2026, 10, 27), LocalDate.of(2026, 11, 9),
                NOW.minus(Duration.ofDays(14)), NOW));
        var successful = mock(CandleBackfillRun.class);
        when(successful.status()).thenReturn("SUCCEEDED");
        when(history.startWithProvider(anyLong(), any(), any(), any(), any()))
                .thenThrow(new IllegalStateException("provider outage"))
                .thenReturn(successful);

        var collector = new OwnerUniverseHistoryBackfill(coverage, history, feed,
                new HistoricalCandleParser(), clock);
        collector.collectOne();
        clock.advance(Duration.ofSeconds(20));
        collector.collectOne();
        verify(history, times(1)).startWithProvider(anyLong(), any(), any(), any(), any());
        clock.advance(Duration.ofSeconds(11));
        collector.collectOne();
        verify(history, times(2)).startWithProvider(anyLong(), any(), any(), any(), any());
        // A successful follow-up clears global backoff immediately.
        collector.collectOne();
        verify(history, times(3)).startWithProvider(anyLong(), any(), any(), any(), any());
    }

    @Test
    void persistedFailedRunAlsoTriggersGlobalBackoff() {
        var coverage = mock(UniverseCandleCoverageService.class);
        var history = mock(HistoricalCandleService.class);
        var feed = mock(AppAngelOneMarketFeedService.class);
        var clock = new ManualClock(NOW);
        when(feed.available()).thenReturn(true);
        when(coverage.nextDue()).thenReturn(Optional.of(42L));
        when(coverage.window()).thenReturn(new UniverseCandleCoverageService.Window(
                LocalDate.of(2026, 10, 27), LocalDate.of(2026, 11, 9),
                NOW.minus(Duration.ofDays(14)), NOW));
        var failed = mock(CandleBackfillRun.class);
        when(failed.id()).thenReturn(UUID.randomUUID());
        when(failed.status()).thenReturn("FAILED");
        when(history.startWithProvider(anyLong(), any(), any(), any(), any()))
                .thenReturn(failed);

        var collector = new OwnerUniverseHistoryBackfill(coverage, history, feed,
                new HistoricalCandleParser(), clock);
        collector.collectOne();
        collector.collectOne();
        verify(history, times(1)).startWithProvider(anyLong(), any(), any(), any(), any());
        verify(coverage, times(1)).nextDue();
    }

    private static final class ManualClock extends Clock {
        private Instant now;

        ManualClock(Instant now) { this.now = now; }

        void advance(Duration duration) { now = now.plus(duration); }

        @Override public java.time.ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(java.time.ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
    }

}
