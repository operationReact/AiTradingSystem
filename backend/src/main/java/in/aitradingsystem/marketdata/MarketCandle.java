package in.aitradingsystem.marketdata;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record MarketCandle(
        long id,
        long instrumentId,
        String exchange,
        String tradingSymbol,
        String symbolToken,
        UUID catalogueVersion,
        String interval,
        Instant candleTime,
        BigDecimal open,
        BigDecimal high,
        BigDecimal low,
        BigDecimal close,
        long volume,
        String source,
        Instant fetchedAt) {}
