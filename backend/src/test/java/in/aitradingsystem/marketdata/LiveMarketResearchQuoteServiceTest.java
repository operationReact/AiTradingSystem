package in.aitradingsystem.marketdata;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import in.aitradingsystem.instrument.Instrument;
import in.aitradingsystem.instrument.InstrumentMasterService;
import in.aitradingsystem.provider.angelone.AngelOneException;
import in.aitradingsystem.provider.angelone.AngelOneFullQuote;
import in.aitradingsystem.provider.angelone.AngelOneFullQuoteService;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class LiveMarketResearchQuoteServiceTest {
    private static final UUID CATALOGUE = UUID.fromString("12345678-1234-1234-1234-123456789abc");
    private static final Instrument INSTRUMENT = new Instrument(
            42L, "NSE", "NSE", "2885", "RELIANCE-EQ", "RELIANCE", "EQ",
            null, BigDecimal.ZERO, 1, new BigDecimal("0.05"), CATALOGUE);

    @Test
    void bindsObservedQuoteToExactActiveCatalogueIdentity() {
        InstrumentMasterService instruments = mock(InstrumentMasterService.class);
        AngelOneFullQuoteService quotes = mock(AngelOneFullQuoteService.class);
        when(instruments.requireActiveInstrument(42L)).thenReturn(INSTRUMENT);
        when(quotes.fullQuote("NSE", "2885")).thenReturn(quote("RELIANCE-EQ", "2885"));

        LiveMarketResearchQuote result = new LiveMarketResearchQuoteService(instruments, quotes).quote(42L);

        assertThat(result.contractVersion()).isEqualTo("angelone-full-quote.v1");
        assertThat(result.instrumentId()).isEqualTo(42L);
        assertThat(result.catalogueVersion()).isEqualTo(CATALOGUE);
        assertThat(result.exchange()).isEqualTo("NSE");
        assertThat(result.tradingSymbol()).isEqualTo("RELIANCE-EQ");
        assertThat(result.symbolToken()).isEqualTo("2885");
        assertThat(result.spreadBps()).isEqualByComparingTo("10.0");
        assertThat(result.source()).isEqualTo("ANGEL_ONE_SMARTAPI_FULL");
        assertThat(result.canAuthorizeCapital()).isFalse();
    }

    @Test
    void failsClosedIfProviderTradingSymbolDoesNotMatchActiveCatalogue() {
        InstrumentMasterService instruments = mock(InstrumentMasterService.class);
        AngelOneFullQuoteService quotes = mock(AngelOneFullQuoteService.class);
        when(instruments.requireActiveInstrument(42L)).thenReturn(INSTRUMENT);
        when(quotes.fullQuote("NSE", "2885")).thenReturn(quote("OTHER-EQ", "2885"));

        assertThatThrownBy(() -> new LiveMarketResearchQuoteService(instruments, quotes).quote(42L))
                .isInstanceOfSatisfying(AngelOneException.class, ex -> {
                    assertThat(ex.kind()).isEqualTo(AngelOneException.Kind.INVALID_RESPONSE);
                    assertThat(ex.code()).isEqualTo("CATALOGUE_QUOTE_IDENTITY_MISMATCH");
                });
    }

    private static AngelOneFullQuote quote(String tradingSymbol, String token) {
        return new AngelOneFullQuote(
                "NSE", tradingSymbol, token, new BigDecimal("100.05"), new BigDecimal("100.00"), 125,
                new BigDecimal("100.10"), 150, new BigDecimal("10.0"), 123456,
                Instant.parse("2026-11-02T09:59:58Z"), Instant.parse("2026-11-02T10:00:05Z"));
    }
}
