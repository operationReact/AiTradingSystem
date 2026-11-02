package in.aitradingsystem.marketdata;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/** Immutable observed-only quote evidence. It is research input and cannot authorize capital. */
public record LiveMarketResearchQuote(
        String contractVersion,
        long instrumentId,
        UUID catalogueVersion,
        String exchange,
        String tradingSymbol,
        String symbolToken,
        BigDecimal ltp,
        BigDecimal bestBidPrice,
        long bestBidQuantity,
        BigDecimal bestAskPrice,
        long bestAskQuantity,
        BigDecimal spreadBps,
        long tradeVolume,
        Instant exchangeFeedTime,
        Instant availableAt,
        String source,
        boolean canAuthorizeCapital) {
}
