package in.aitradingsystem.marketdata;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

class MarketInstrumentTest {
    @Test
    void representsInstrumentMetadataWithoutBrokerCredentials() {
        MarketInstrument instrument = new MarketInstrument(
            1L, "NSE", "12345", "RELIANCE-EQ", "RELIANCE", "EQ",
            null, BigDecimal.ZERO, 1, new BigDecimal("0.05"));

        assertEquals("NSE", instrument.exchange());
        assertEquals("12345", instrument.symbolToken());
        assertEquals("RELIANCE-EQ", instrument.tradingSymbol());
        assertNull(instrument.expiry());
    }
}