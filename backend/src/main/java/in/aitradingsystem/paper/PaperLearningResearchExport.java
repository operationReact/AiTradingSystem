package in.aitradingsystem.paper;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Versioned, read-only bridge from authoritative Java PAPER execution evidence to offline learning.
 * It intentionally contains execution/outcome lineage only; model, Gemini and risk fingerprints must
 * come from their own persisted evidence and must never be fabricated by this export.
 */
public record PaperLearningResearchExport(
        String contractVersion,
        Instant generatedAt,
        List<ClosedTrade> trades) {

    public record ClosedTrade(
            UUID allocationId,
            UUID lotId,
            UUID entryOrderId,
            UUID entryFillId,
            UUID exitOrderId,
            UUID exitFillId,
            Long instrumentId,
            String exchange,
            String symbol,
            String productType,
            String executionOrigin,
            int quantity,
            BigDecimal expectedEntryPrice,
            BigDecimal entryPrice,
            BigDecimal exitPrice,
            BigDecimal allocatedEntryFeeAmount,
            BigDecimal allocatedExitFeeAmount,
            BigDecimal allocatedEntrySlippageAmount,
            BigDecimal allocatedExitSlippageAmount,
            BigDecimal allocatedRealizedPnl,
            String exitReason,
            Instant openedAt,
            Instant closedAt) {}
}
