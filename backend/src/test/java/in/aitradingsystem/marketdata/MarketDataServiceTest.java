package in.aitradingsystem.marketdata;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import in.aitradingsystem.instrument.Instrument;
import in.aitradingsystem.instrument.InstrumentMasterService;
import in.aitradingsystem.provider.angelone.AngelOneHistoricalService;
import java.time.LocalDate;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class MarketDataServiceTest {
    @Test
    void rejectsUnsupportedExchangeBeforeCreatingBackfill() {
        InstrumentMasterService instruments = mock(InstrumentMasterService.class);
        AngelOneHistoricalService provider = mock(AngelOneHistoricalService.class);
        MarketDataRepository repository = mock(MarketDataRepository.class);
        HistoricalCandleService service = new HistoricalCandleService(instruments, provider,
                new HistoricalCandleParser(), repository, 366, 5);
        when(instruments.requireActiveInstrument(anyLong())).thenReturn(new Instrument(1, "CDS", "CDS",
                "token", "USDINR", "Currency", "FUT", null, null, 1,
                new java.math.BigDecimal("0.0025"), UUID.randomUUID()));

        assertThatThrownBy(() -> service.start(1, HistoricalCandleInterval.ONE_DAY,
                LocalDate.parse("2026-10-26"), LocalDate.parse("2026-10-26")))
            .isInstanceOf(MarketDataException.class).hasMessageContaining("NSE, NFO, BSE, BFO and MCX");
        org.mockito.Mockito.verifyNoInteractions(provider, repository);
    }

    @Test
    void rejectsDateRangeOutsideConfiguredLimit() {
        HistoricalCandleService service = new HistoricalCandleService(mock(InstrumentMasterService.class),
                mock(AngelOneHistoricalService.class), new HistoricalCandleParser(), mock(MarketDataRepository.class), 30, 5);
        assertThatThrownBy(() -> service.query(1, HistoricalCandleInterval.ONE_DAY,
                LocalDate.parse("2026-01-01"), LocalDate.parse("2026-02-01"), 0, 100))
            .isInstanceOf(MarketDataException.class).hasMessageContaining("limited to 30");
    }
}
