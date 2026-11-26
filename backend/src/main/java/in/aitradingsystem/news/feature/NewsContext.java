package in.aitradingsystem.news.feature;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import in.aitradingsystem.news.analysis.NewsAnalysisParser.FinancialMetric;

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
    List<String> limitations,
    /** True when any included item is inside its blackout window at asOf. Direction is then not trusted. */
    boolean blackout,
    /** LISTED_FNO, NOT_LISTED or UNKNOWN_CATALOG from FnoUnderlyingPolicy; null when not resolved. */
    String fnoStatus,
    /** Resolved F&amp;O underlying symbol when LISTED_FNO; otherwise null. */
    String fnoUnderlying) {

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
        boolean included,
        /** Advisory event class from the analyzer category (NewsEventClassifier.EventClass name). */
        String eventClass,
        /** Fine-grained event label from the immutable article analysis. */
        String eventType,
        /** Source-evidenced figures extracted from the article; never estimates. */
        List<FinancialMetric> financialMetrics,
        /** FRESH, AGING or UNKNOWN relative to the item's validity window at asOf. */
        String freshness,
        /** End of this item's blackout window, or null when it opens none. */
        Instant blackoutUntil) {
        /** Backward-compatible constructor for existing fixtures and stored-context tooling. */
        public Evidence(String articleId, String source, String title, String url, Instant publishedAt,
                Instant analysisAvailableAt, Instant validUntil, String horizon, String sentiment,
                BigDecimal directionScore, BigDecimal confidence, BigDecimal materiality, String provider,
                String model, String evidenceSummary, String status, boolean included, String eventClass,
                String freshness, Instant blackoutUntil) {
            this(articleId, source, title, url, publishedAt, analysisAvailableAt, validUntil, horizon,
                sentiment, directionScore, confidence, materiality, provider, model, evidenceSummary,
                status, included, eventClass, "UNKNOWN", List.of(), freshness, blackoutUntil);
        }
    }

    public static final List<String> LIMITATIONS = List.of(
        "Advisory LLM classification of article text. Direction is evidence direction, not a trade recommendation.",
        "analysisAvailableAt is the analysis creation time; it is the earliest moment the classification was knowable.",
        "Publisher source rights are checked at lookup for current approval and at the research cutoff; the returned annotation is not a reusable content licence or issuer certification.",
        "Research context only. It does not change candidacy, score, risk, sizing or order placement.");

    /** Copy with the deterministic F&amp;O underlying resolution attached. Does not change any news fact. */
    public NewsContext withFno(FnoUnderlyingPolicy.Resolution resolution) {
        return new NewsContext(contract, instrumentId, tradingSymbol, asOf, status, netDirection, direction,
            uncertainty, includedCount, excludedCount, evidence, limitations, blackout,
            resolution == null ? null : resolution.status().name(),
            resolution == null ? null : resolution.underlying());
    }

    /** Context for an as-of lookup that failed. Never reported as bullish, bearish or neutral. */
    public static NewsContext unavailable(long instrumentId, String tradingSymbol, Instant asOf) {
        return new NewsContext(NewsFeaturePolicy.CONTRACT, instrumentId, tradingSymbol, asOf,
            STATUS_UNAVAILABLE, null, "UNKNOWN", "UNKNOWN", 0, 0, List.of(), LIMITATIONS,
            false, null, null);
    }
}
