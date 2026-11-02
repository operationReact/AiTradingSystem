package in.aitradingsystem.marketdata;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import in.aitradingsystem.instrument.Instrument;
import in.aitradingsystem.instrument.InstrumentMasterService;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class MarketDataResearchExportServiceTest {

    @Test
    void exportsIntradayBarsAtTheirFirstSafeAvailabilityTime() {
        HistoricalCandleService candles = mock(HistoricalCandleService.class);
        InstrumentMasterService instruments = mock(InstrumentMasterService.class);
        MarketDataResearchExportService service = new MarketDataResearchExportService(candles, instruments);
        UUID catalogueVersion = UUID.randomUUID();
        Instrument instrument = new Instrument(1, "NSE", "NSE", "2885", "RELIANCE-EQ", "Reliance",
                "EQ", null, null, 1, new BigDecimal("0.05"), catalogueVersion);
        Instant candleTime = Instant.parse("2026-11-02T03:45:00Z"); // 09:15 IST
        MarketCandle candle = new MarketCandle(10, 1, "NSE", "RELIANCE-EQ", "2885", catalogueVersion,
                "FIVE_MINUTE", candleTime, new BigDecimal("2948"), new BigDecimal("2955"),
                new BigDecimal("2945"), new BigDecimal("2952"), 1200, "ANGEL_ONE_SMARTAPI",
                Instant.parse("2026-11-02T12:00:00Z"));
        LocalDate date = LocalDate.parse("2026-11-02");

        when(instruments.requireActiveInstrument(1)).thenReturn(instrument);
        when(candles.query(1, HistoricalCandleInterval.FIVE_MINUTE, date, date, 0, 500))
                .thenReturn(new CandlePage(List.of(candle), 0, 500, 1, 1, false));

        MarketDataResearchExport result = service.export(
                1, HistoricalCandleInterval.FIVE_MINUTE, date, date, 20_000);

        assertThat(result.contractVersion()).isEqualTo("market-history.v1");
        assertThat(result.catalogueVersion()).isEqualTo(catalogueVersion);
        assertThat(result.providerSources()).containsExactly("ANGEL_ONE_SMARTAPI");
        assertThat(result.candles()).hasSize(1);
        assertThat(result.candles().getFirst().candleTime()).isEqualTo(candleTime);
        assertThat(result.candles().getFirst().availableAt())
                .isEqualTo(Instant.parse("2026-11-02T03:50:00Z"));
        assertThat(result.dataCutoff()).isEqualTo(Instant.parse("2026-11-02T03:50:00Z"));
        assertThat(result.candles().getFirst().fetchedAt())
                .isEqualTo(Instant.parse("2026-11-02T12:00:00Z"));
    }

    @Test
    void dailyBarIsNotAvailableBeforeIndianCashMarketClose() {
        Instant midnightIst = Instant.parse("2026-11-01T18:30:00Z");

        assertThat(MarketDataResearchExportService.availableAt(
                midnightIst, HistoricalCandleInterval.ONE_DAY))
                .isEqualTo(Instant.parse("2026-11-02T10:00:00Z"));
    }

    @Test
    void rejectsUnboundedResearchExports() {
        MarketDataResearchExportService service = new MarketDataResearchExportService(
                mock(HistoricalCandleService.class), mock(InstrumentMasterService.class));

        assertThatThrownBy(() -> service.export(1, HistoricalCandleInterval.ONE_DAY,
                LocalDate.parse("2026-11-01"), LocalDate.parse("2026-11-01"), 20_001))
                .isInstanceOf(MarketDataException.class)
                .hasMessageContaining("between 1 and 20000");
    }
}
