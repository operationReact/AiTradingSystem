package in.aitradingsystem.marketdata;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record MarketDataResearchExport(
        String contractVersion,
        Instant generatedAt,
        long instrumentId,
        String exchange,
        String tradingSymbol,
        String symbolToken,
        UUID catalogueVersion,
        String interval,
        Instant fromInclusive,
        Instant toExclusive,
        Instant dataCutoff,
        List<String> providerSources,
        List<ResearchCandle> candles) {

    public record ResearchCandle(
            Instant candleTime,
            Instant availableAt,
            BigDecimal open,
            BigDecimal high,
            BigDecimal low,
            BigDecimal close,
            long volume,
            String source,
            Instant fetchedAt) {}
}
