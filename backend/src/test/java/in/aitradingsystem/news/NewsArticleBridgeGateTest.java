package in.aitradingsystem.news;

import static org.assertj.core.api.Assertions.assertThat;

import in.aitradingsystem.news.NewsArticleBridge.Outcome;
import java.time.Instant;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** Fail-closed gate for one RSS-derived article. Pure logic: no database, no network. */
class NewsArticleBridgeGateTest {
    private static final Instant NOW = Instant.parse("2026-11-24T10:00:00Z");
    private static final Set<String> APPROVED = Set.of("moneycontrol");
    private static final String URL = "https://www.moneycontrol.com/news/a-1.html";

    private static Outcome gate(String source, String url, String title, Instant publishedAt) {
        Outcome o = NewsArticleBridge.gate(source, url, title, publishedAt, APPROVED, NOW);
        return o; // null means eligible
    }

    @Test
    void approvedFreshArticleIsEligible() {
        assertThat(gate("moneycontrol", URL, "Headline", NOW.minusSeconds(3600))).isNull();
    }

    @Test
    void unapprovedSourceNeverPublishes() {
        assertThat(gate("economic-times", URL, "Headline", NOW.minusSeconds(3600)))
            .isEqualTo(Outcome.SKIPPED_UNAPPROVED_SOURCE);
    }

    @Test
    void emptyApprovalListIsDefaultDenied() {
        assertThat(NewsArticleBridge.gate("moneycontrol", URL, "Headline", NOW.minusSeconds(60),
            Set.of(), NOW)).isEqualTo(Outcome.SKIPPED_UNAPPROVED_SOURCE);
    }

    @Test
    void missingFieldsAreRejectedBeforeAnyOtherCheck() {
        assertThat(gate(null, URL, "Headline", NOW)).isEqualTo(Outcome.SKIPPED_MISSING_FIELDS);
        assertThat(gate("moneycontrol", null, "Headline", NOW)).isEqualTo(Outcome.SKIPPED_MISSING_FIELDS);
        assertThat(gate("moneycontrol", URL, "  ", NOW)).isEqualTo(Outcome.SKIPPED_MISSING_FIELDS);
        assertThat(gate("moneycontrol", URL, "Headline", null)).isEqualTo(Outcome.SKIPPED_MISSING_FIELDS);
    }

    @Test
    void nonHttpUrlsAreRejected() {
        assertThat(gate("moneycontrol", "javascript:alert(1)", "Headline", NOW.minusSeconds(60)))
            .isEqualTo(Outcome.SKIPPED_INVALID_URL);
        assertThat(gate("moneycontrol", "file:///etc/passwd", "Headline", NOW.minusSeconds(60)))
            .isEqualTo(Outcome.SKIPPED_INVALID_URL);
        assertThat(gate("moneycontrol", "https://", "Headline", NOW.minusSeconds(60)))
            .isEqualTo(Outcome.SKIPPED_INVALID_URL);
    }

    @Test
    void futureDatedArticlesAreRejectedBeyondTolerance() {
        assertThat(gate("moneycontrol", URL, "Headline", NOW.plusSeconds(3600)))
            .isEqualTo(Outcome.SKIPPED_FUTURE_DATED);
        // Small clock skew is tolerated.
        assertThat(gate("moneycontrol", URL, "Headline", NOW.plusSeconds(60))).isNull();
    }

    @Test
    void staleArticlesOutsideFreshnessWindowAreRejected() {
        assertThat(gate("moneycontrol", URL, "Headline", NOW.minusSeconds(31L * 86_400)))
            .isEqualTo(Outcome.SKIPPED_STALE);
        assertThat(gate("moneycontrol", URL, "Headline", NOW.minusSeconds(29L * 86_400))).isNull();
    }

    @Test
    void httpAndHttpsAreTheOnlySchemes() {
        assertThat(NewsArticleBridge.validHttpUrl("https://example.test/a")).isTrue();
        assertThat(NewsArticleBridge.validHttpUrl("http://example.test/a")).isTrue();
        assertThat(NewsArticleBridge.validHttpUrl("ftp://example.test/a")).isFalse();
    }
}
