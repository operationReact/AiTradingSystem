package in.aitradingsystem.provider.angelone;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Strict read-only SmartAPI FULL quote evidence for one exact provider instrument identity.
 * availableAt is the local capture time and is the causality boundary for downstream research.
 */
public record AngelOneFullQuote(
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
        Instant availableAt) {
}
