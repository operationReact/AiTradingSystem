package in.aitradingsystem.marketdata;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import in.aitradingsystem.instrument.Instrument;
import in.aitradingsystem.instrument.InstrumentMasterService;
import java.math.BigDecimal;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class MarketQuoteControllerTest {
    @Test
    void quoteUsesCanonicalInstrumentAndKeepsProviderTokensOutOfResponse() {
        InstrumentMasterService instruments = mock(InstrumentMasterService.class);
        MarketDataProvider provider = mock(MarketDataProvider.class);
        Instrument instrument = new Instrument(42L, "NSE_EQ", "NSE", "private-broker-token",
                "TEST-EQ", "Test Equity", "EQ", null, null, 1,
                new BigDecimal("0.05"), UUID.randomUUID());
        when(instruments.requireActiveInstrument(42L)).thenReturn(instrument);
        when(provider.lastTradedPrice(instrument)).thenReturn(new BigDecimal("135.55"));
        when(provider.source()).thenReturn("ANGEL_ONE_SMARTAPI");

        MarketQuoteController.Quote quote = new MarketQuoteController(instruments, provider).quote(42L);

        assertThat(quote.instrumentId()).isEqualTo(42L);
        assertThat(quote.exchange()).isEqualTo("NSE");
        assertThat(quote.tradingSymbol()).isEqualTo("TEST-EQ");
        assertThat(quote.ltp()).isEqualByComparingTo("135.55");
        assertThat(quote.source()).isEqualTo("ANGEL_ONE_SMARTAPI");
        assertThat(quote.fetchedAt()).isNotNull();
        assertThat(quote.toString()).doesNotContain("private-broker-token");
        verify(provider).lastTradedPrice(instrument);
    }
}
