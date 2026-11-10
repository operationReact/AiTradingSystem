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
        var window = service.window();
        when(repo.nextDue(window.fromInclusive(), window.toExclusive(),
                NOW.minus(Duration.ofHours(18)))).thenReturn(Optional.of(42L));
        var run = mock(CandleBackfillRun.class);
        when(run.status()).thenReturn("SUCCEEDED");
        when(run.id()).thenReturn(UUID.randomUUID());
        when(history.startWithProvider(42L, HistoricalCandleInterval.FIVE_MINUTE,
                LocalDate.of(2026, 10, 27), LocalDate.of(2026, 11, 9), any(MarketDataProvider.class)))
                .thenReturn(run);

        new OwnerUniverseHistoryBackfill(service, history,
                mock(AppAngelOneMarketFeedService.class), new HistoricalCandleParser()).collectOne();

        verify(history, times(1)).start(42L, HistoricalCandleInterval.FIVE_MINUTE,
                LocalDate.of(2026, 10, 27), LocalDate.of(2026, 11, 9), any(MarketDataProvider.class));
        verifyNoMoreInteractions(history);
    }

    @Test
    void noDueInstrumentNeverCallsProvider() {
        var repo = mock(UniverseCandleCoverageRepository.class);
        var service = new UniverseCandleCoverageService(repo, Clock.fixed(NOW, ZoneOffset.UTC));
        var history = mock(HistoricalCandleService.class);

        new OwnerUniverseHistoryBackfill(service, history,
                mock(AppAngelOneMarketFeedService.class), new HistoricalCandleParser()).collectOne();

        verifyNoInteractions(history);
    }

    @Test
    void providerFailureDoesNotKillFutureSchedulerCycles() {
        var repo = mock(UniverseCandleCoverageRepository.class);
        var service = new UniverseCandleCoverageService(repo, Clock.fixed(NOW, ZoneOffset.UTC));
        var history = mock(HistoricalCandleService.class);
        var w = service.window();
        when(repo.nextDue(w.fromInclusive(), w.toExclusive(),
                NOW.minus(Duration.ofHours(18)))).thenReturn(Optional.of(42L));
        when(history.startWithProvider(anyLong(), any(), any(), any(), any()))
                .thenThrow(new IllegalStateException("provider not configured"));

        assertThatCode(() -> new OwnerUniverseHistoryBackfill(service, history,
                mock(AppAngelOneMarketFeedService.class), new HistoricalCandleParser()).collectOne())
                .doesNotThrowAnyException();
        verify(history, times(1)).startWithProvider(anyLong(), any(), any(), any(), any());
    }
}
