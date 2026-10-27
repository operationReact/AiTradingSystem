package in.aitradingsystem.instrument;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

public record Instrument(
        long id,
        String exchangeSegment,
        String exchange,
        String symbolToken,
        String tradingSymbol,
        String name,
        String instrumentType,
        LocalDate expiry,
        BigDecimal strike,
        int lotSize,
        BigDecimal tickSize,
        UUID catalogueVersion) {}
