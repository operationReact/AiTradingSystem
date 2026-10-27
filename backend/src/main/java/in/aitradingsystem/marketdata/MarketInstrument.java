package in.aitradingsystem.marketdata;

import java.math.BigDecimal;
import java.time.LocalDate;

public record MarketInstrument(
    long id,
    String exchange,
    String symbolToken,
    String tradingSymbol,
    String name,
    String instrumentType,
    LocalDate expiry,
    BigDecimal strikePrice,
    Integer lotSize,
    BigDecimal tickSize
) {}