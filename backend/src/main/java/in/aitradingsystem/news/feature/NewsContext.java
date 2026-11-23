package in.aitradingsystem.news.feature;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/**
 * Advisory, as-of news context for one instrument. Research annotation only: it never changes
 * candidacy, score, risk, sizing or order placement.
 */
public record NewsContext(
    String contract,
    long instrumentId,
    String tradingSymbol,
    Instant asOf,
    String status,
    BigDecimal netDirection,
    String direction,
    String uncertainty,
    int includedCount,
    int excludedCount,
    List<Evidence> evidence,
    List<String> limitations) {

    /** Status values. UNAVAILABLE is produced when the lookup fails; the scan itself continues. */
    public static final String STATUS_NO_COVERAGE = "NO_COVERAGE";
    public static final String STATUS_NO_INCLUDED_EVIDENCE = "NO_INCLUDED_EVIDENCE";
    public static final String STATUS_INCLUDED_EVIDENCE = "INCLUDED_EVIDENCE";
    public static final String STATUS_UNAVAILABLE = "UNAVAILABLE";

    /** One source-attributed analyzed article and why it was included or excluded. */
    public record Evidence(
        String articleId,
        String source,
        String title,
        String url,
        Instant publishedAt,
        Instant analysisAvailableAt,
        Instant validUntil,
        String horizon,
        String sentiment,
        BigDecimal directionScore,
        BigDecimal confidence,
        BigDecimal materiality,
        String provider,
        String model,
        String evidenceSummary,
        String status,
        boolean included) {}

    public static final List<String> LIMITATIONS = List.of(
        "Advisory LLM classification of article text. Direction is evidence direction, not a trade recommendation.",
        "analysisAvailableAt is the analysis creation time; it is the earliest moment the classification was knowable.",
        "Source licence and redistribution rights for each source are not recorded by this context (BLOCKED).",
        "Research context only. It does not change candidacy, score, risk, sizing or order placement.");

    /** Context for an as-of lookup that failed. Never reported as bullish, bearish or neutral. */
    public static NewsContext unavailable(long instrumentId, String tradingSymbol, Instant asOf) {
        return new NewsContext(NewsFeaturePolicy.CONTRACT, instrumentId, tradingSymbol, asOf,
            STATUS_UNAVAILABLE, null, "UNKNOWN", "UNKNOWN", 0, 0, List.of(), LIMITATIONS);
    }
}
