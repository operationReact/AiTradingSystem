package in.aitradingsystem.provider.upstox;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

import in.aitradingsystem.marketdata.GoldenHistoricalDatasetProvider;
import in.aitradingsystem.marketdata.GoldenSourceRightsRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

class UpstoxGoldenHistoricalProviderTest {
    private final UpstoxAnalyticsSource source = mock(UpstoxAnalyticsSource.class);
    private final GoldenSourceRightsRepository rights = mock(GoldenSourceRightsRepository.class);
    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final Clock clock = Clock.fixed(Instant.parse("2026-11-16T08:00:00Z"), ZoneOffset.UTC);
    private final UpstoxGoldenHistoricalProvider provider =
            new UpstoxGoldenHistoricalProvider(source, rights, jdbc, clock);

    private GoldenHistoricalDatasetProvider.ChunkRequest request(String interval) {
        return new GoldenHistoricalDatasetProvider.ChunkRequest(
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                interval, LocalDate.of(2026, 11, 10), LocalDate.of(2026, 11, 10), 8192);
    }

    @Test
    void disabledSourceCannotReadEvenWithAnyRightsReceipt() {
        assertThatThrownBy(() -> provider.fetch(request("DAY_1")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("RIGHTS_OR_SOURCE_DISABLED");
        verifyNoInteractions(rights, jdbc);
        verify(source, never()).historicalEquityCandles(
                anyString(), anyString(), anyInt(), any(), any());
    }

    @Test
    void missingRetentionRightPreventsAliasLookupAndProviderRead() {
        when(source.enabled()).thenReturn(true);
        assertThatThrownBy(() -> provider.fetch(request("DAY_1")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("RIGHTS_OR_SOURCE_DISABLED");
        verify(rights).isPermitted(eq("UPSTOX"), eq("historical_equity"),
                eq(GoldenSourceRightsRepository.Use.RAW_RETENTION), eq(clock.instant()));
        verifyNoInteractions(jdbc);
        verify(source, never()).historicalEquityCandles(
                anyString(), anyString(), anyInt(), any(), any());
    }

    @Test
    void unsupportedTimeframeRejectedBeforeAnyDatabaseOrMarketRead() {
        when(source.enabled()).thenReturn(true);
        when(rights.isPermitted(eq("UPSTOX"), eq("historical_equity"),
                eq(GoldenSourceRightsRepository.Use.RAW_RETENTION), any()))
                .thenReturn(true);
        assertThatThrownBy(() -> provider.fetch(request("WEEK_1")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("UNSUPPORTED_UPSTOX_GOLDEN_INTERVAL");
        verifyNoInteractions(jdbc);
        verify(source, never()).historicalEquityCandles(
                anyString(), anyString(), anyInt(), any(), any());
    }
    @Test
    void missingDurableChunkIdentityRejectedBeforeDatabaseOrProviderRead() {
        when(source.enabled()).thenReturn(true);
        when(rights.isPermitted(eq("UPSTOX"), eq("historical_equity"),
                eq(GoldenSourceRightsRepository.Use.RAW_RETENTION), any()))
                .thenReturn(true);
        var missingJob = new GoldenHistoricalDatasetProvider.ChunkRequest(
                null, UUID.randomUUID(), UUID.randomUUID(), "DAY_1",
                LocalDate.of(2026, 11, 10), LocalDate.of(2026, 11, 10), 8192);
        assertThatThrownBy(() -> provider.fetch(missingJob))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("INVALID_UPSTOX_GOLDEN_WINDOW");
        verifyNoInteractions(jdbc);
        verify(source, never()).historicalEquityCandles(
                anyString(), anyString(), anyInt(), any(), any());
    }

}
