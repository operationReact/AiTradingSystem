package in.aitradingsystem.news.feature;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

/** The audit must catch planted future evidence; a passing audit with no detection proves nothing. */
class NewsLeakageAuditTest {
    private static final Instant SIGNAL = Instant.parse("2026-11-20T10:00:00Z");

    private static NewsContext.Evidence evidence(Instant publishedAt, Instant analysisAt, boolean included,
                                                 Instant validUntil) {
        return new NewsContext.Evidence("article-1", "SRC", "t", "u", publishedAt, analysisAt, validUntil,
            "INTRADAY", "POSITIVE", new BigDecimal("0.5"), new BigDecimal("0.8"), new BigDecimal("0.7"),
            "TEST", "m", "s", included ? "INCLUDED" : "EXCLUDED_EXPIRED", included,
            "GENERAL", "FRESH", null);
    }

    private static NewsContext context(NewsContext.Evidence... items) {
        return new NewsContext(NewsFeaturePolicy.CONTRACT, 1L, "X-EQ", SIGNAL,
            NewsContext.STATUS_INCLUDED_EVIDENCE, null, "UNKNOWN", "UNKNOWN", 0, 0, List.of(items),
            NewsContext.LIMITATIONS, false, null, null);
    }

    @Test
    void cleanContextHasNoViolations() {
        NewsContext c = context(evidence(SIGNAL.minusSeconds(3600), SIGNAL.minusSeconds(60), true,
            SIGNAL.plusSeconds(3600)));
        assertThat(NewsBacktestAnnotator.leakageViolations(c)).isEmpty();
    }

    @Test
    void detectsArticlePublishedAfterSignal() {
        NewsContext c = context(evidence(SIGNAL.plusSeconds(1), SIGNAL.minusSeconds(60), false, SIGNAL));
        assertThat(NewsBacktestAnnotator.leakageViolations(c))
            .anyMatch(v -> v.startsWith("published after as-of"));
    }

    @Test
    void detectsAnalysisCreatedAfterSignal() {
        NewsContext c = context(evidence(SIGNAL.minusSeconds(3600), SIGNAL.plusSeconds(5), false, SIGNAL));
        assertThat(NewsBacktestAnnotator.leakageViolations(c))
            .anyMatch(v -> v.startsWith("analysis created after as-of"));
    }

    @Test
    void detectsIncludedEvidenceBeyondValidity() {
        NewsContext c = context(evidence(SIGNAL.minusSeconds(3600), SIGNAL.minusSeconds(60), true,
            SIGNAL.minusSeconds(1)));
        assertThat(NewsBacktestAnnotator.leakageViolations(c))
            .anyMatch(v -> v.startsWith("included after validity expired"));
    }
}
