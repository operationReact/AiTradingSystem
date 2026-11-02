package in.aitradingsystem.instrument;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Immutable read-only research export of one causally available Angel One cash-market catalogue.
 * This is a candidate universe source, not a liquidity-qualified V3 trading universe and never capital authority.
 */
public record InstrumentCatalogueResearchExport(
        String contractVersion,
        Instant generatedAt,
        Instant asOf,
        Instant dataCutoff,
        UUID catalogueVersion,
        Instant catalogueAvailableAt,
        String sourceUrl,
        int sourceAcceptedCount,
        int sourceRejectedCount,
        List<String> exchanges,
        String instrumentType,
        int rowCount,
        String catalogueFingerprint,
        String status,
        boolean canAuthorizeCapital,
        List<ResearchInstrument> instruments) {

    public record ResearchInstrument(
            long instrumentId,
            String exchangeSegment,
            String exchange,
            String symbolToken,
            String tradingSymbol,
            String name,
            String instrumentType,
            LocalDate expiry,
            BigDecimal strike,
            int lotSize,
            BigDecimal tickSize) {}
}
