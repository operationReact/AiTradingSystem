package in.aitradingsystem.marketdata;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class MarketDataReconciliationServiceTest {
    private final MarketDataQualityService quality = mock(MarketDataQualityService.class);
    private final HistoricalCandleService historical = mock(HistoricalCandleService.class);

    @Test
    void contiguousMissingSlotsAreCoalescedIntoOneGapWindow() {
        MarketDataReconciliationService service = new MarketDataReconciliationService(quality, historical, false, 5);
        Instant t = Instant.parse("2026-10-29T03:45:00Z");
        MarketDataQualityResult result = new MarketDataQualityResult(1, HistoricalCandleInterval.FIVE_MINUTE,
                t, t.plusSeconds(1800), 6, 3, 3, 0, 0, "DEGRADED",
                List.of(new MarketDataQualityResult.GapRange(t.plusSeconds(300), t.plusSeconds(300)),
                        new MarketDataQualityResult.GapRange(t.plusSeconds(600), t.plusSeconds(600)),
                        new MarketDataQualityResult.GapRange(t.plusSeconds(1500), t.plusSeconds(1500))));

        List<GapWindow> gaps = service.findMissingWindows(result, HistoricalCandleInterval.FIVE_MINUTE);

        assertThat(gaps).containsExactly(
                new GapWindow(t.plusSeconds(300), t.plusSeconds(900), 2),
                new GapWindow(t.plusSeconds(1500), t.plusSeconds(1800), 1));
    }

    @Test
    void defaultModeReportsGapsWithoutStartingProviderBackfill() {
        MarketDataReconciliationService service = new MarketDataReconciliationService(quality, historical, false, 5);
        Instant from = Instant.parse("2026-10-29T03:45:00Z");
        Instant to = from.plusSeconds(600);
        MarketDataQualityResult result = new MarketDataQualityResult(7, HistoricalCandleInterval.FIVE_MINUTE,
                from, to, 2, 1, 1, 0, 0, "DEGRADED",
                List.of(new MarketDataQualityResult.GapRange(from.plusSeconds(300), from.plusSeconds(300))));
        when(quality.evaluate(7, HistoricalCandleInterval.FIVE_MINUTE, from, to)).thenReturn(result);

        ReconciliationResult reconciled = service.reconcile(7, "FIVE_MINUTE", from, to);

        assertThat(reconciled.autoBackfillEnabled()).isFalse();
        assertThat(reconciled.backfillsStarted()).isZero();
        assertThat(reconciled.gaps()).hasSize(1);
        verifyNoInteractions(historical);
    }
}
