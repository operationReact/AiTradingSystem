package in.aitradingsystem.news;

import static org.assertj.core.api.Assertions.assertThat;

import in.aitradingsystem.support.AbstractPostgresIT;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Real-Postgres proof that only approved, fresh, HTTP(S) RSS rows reach the analyzer store, with
 * provenance preserved and deduplication by URL. The fixture rows are wiring data, not real news.
 */
class NewsArticleBridgeIT extends AbstractPostgresIT {
    @Autowired JdbcClient jdbc;
    @Autowired NewsService news;
    @Autowired NewsSourceHealthRepository health;

    private static String hash64() {
        return (UUID.randomUUID().toString() + UUID.randomUUID().toString()).replace("-", "");
    }

    /** Inserts an RSS-ingested row exactly as NewsRepository would (news_item). */
    private String rssRow(String source, String url, String title, Instant publishedAt) {
        jdbc.sql("""
                INSERT INTO news_item (source, publisher, source_url, published_at, title,
                                       normalized_content, content_hash)
                VALUES (:source, :publisher, :url, :publishedAt, :title, 'fixture body', :hash)
                """)
            .param("source", source)
            .param("publisher", "Fixture Publisher")
            .param("url", url)
            .param("publishedAt", OffsetDateTime.ofInstant(publishedAt, ZoneOffset.UTC))
            .param("title", title)
            .param("hash", hash64())
            .update();
        return url;
    }

    private long articleCount(String url) {
        return jdbc.sql("SELECT count(*) FROM news_articles WHERE url = :url")
            .param("url", url).query(Long.class).single();
    }

    private NewsArticleBridge bridge(List<String> approved) {
        return new NewsArticleBridge(jdbc, news, health, approved, Clock.systemUTC());
    }

    @Test
    void onlyApprovedFreshRowsArePublishedWithProvenanceAndDeduplicated() {
        String suffix = UUID.randomUUID().toString();
        Instant now = Instant.now();
        String approvedUrl = "https://www.moneycontrol.com/news/bridge-" + suffix + ".html";
        String otherSourceUrl = "https://economictimes.indiatimes.com/bridge-" + suffix + ".cms";
        String staleUrl = "https://www.moneycontrol.com/news/stale-" + suffix + ".html";
        String futureUrl = "https://www.moneycontrol.com/news/future-" + suffix + ".html";
        rssRow("moneycontrol", approvedUrl, "Approved headline", now.minusSeconds(3600));
        rssRow("economic-times", otherSourceUrl, "Unapproved headline", now.minusSeconds(3600));
        rssRow("moneycontrol", staleUrl, "Stale headline", now.minusSeconds(40L * 86_400));
        rssRow("moneycontrol", futureUrl, "Future headline", now.plusSeconds(7200));

        NewsArticleBridge b = bridge(List.of("moneycontrol"));
        b.runOnce();

        assertThat(articleCount(approvedUrl)).isEqualTo(1);
        assertThat(articleCount(otherSourceUrl)).isZero();
        assertThat(articleCount(staleUrl)).isZero();
        assertThat(articleCount(futureUrl)).isZero();

        // Provenance: the analyzer row keeps source id and canonical URL.
        String source = jdbc.sql("SELECT source FROM news_articles WHERE url = :url")
            .param("url", approvedUrl).query(String.class).single();
        assertThat(source).isEqualTo("moneycontrol");

        // Deduplication: a second pass over the same RSS rows does not create a second article.
        b.runOnce();
        assertThat(articleCount(approvedUrl)).isEqualTo(1);
    }

    @Test
    void sourceHealthRecordsEachBridgeRunPerApprovedSource() {
        String suffix = UUID.randomUUID().toString();
        rssRow("moneycontrol", "https://www.moneycontrol.com/news/health-" + suffix + ".html",
            "Health headline", Instant.now().minusSeconds(600));
        bridge(List.of("moneycontrol")).runOnce();

        var latest = health.latest().stream()
            .filter(h -> "moneycontrol".equals(h.source())).findFirst();
        assertThat(latest).isPresent();
        assertThat(latest.get().status()).isEqualTo("SUCCESS");
        assertThat(latest.get().fetchedItems()).isGreaterThanOrEqualTo(1);
    }

    @Test
    void emptyApprovalListPublishesNothing() {
        String url = "https://www.moneycontrol.com/news/none-" + UUID.randomUUID() + ".html";
        rssRow("moneycontrol", url, "Not approved", Instant.now().minusSeconds(600));
        bridge(List.of()).runOnce();
        assertThat(articleCount(url)).isZero();
    }

    /**
     * A row that passes the RSS-side gate but cannot be stored (a URL longer than the analyzer store's
     * 2000-character column) must make the source PARTIAL, never SUCCESS. The good row still publishes.
     */
    @Test
    void rowFailureMakesSourceHealthPartialNotSuccess() {
        String suffix = UUID.randomUUID().toString();
        String goodUrl = "https://www.moneycontrol.com/news/partial-good-" + suffix + ".html";
        String oversizedUrl = "https://www.moneycontrol.com/news/partial-long-" + suffix + "-"
            + "a".repeat(2100) + ".html";
        rssRow("moneycontrol", goodUrl, "Good headline", Instant.now().minusSeconds(600));
        rssRow("moneycontrol", oversizedUrl, "Oversized URL headline", Instant.now().minusSeconds(600));

        try {
            bridge(List.of("moneycontrol")).runOnce();

            assertThat(articleCount(goodUrl)).isEqualTo(1);
            assertThat(articleCount(oversizedUrl)).isZero();
            var latest = health.latest().stream()
                .filter(h -> "moneycontrol".equals(h.source())).findFirst();
            assertThat(latest).isPresent();
            assertThat(latest.get().status()).isEqualTo("PARTIAL");
            assertThat(latest.get().errorCode()).isEqualTo("ROW_INGEST_FAILED");
            assertThat(latest.get().errorDetail()).contains("eligible rows failed");
        } finally {
            // The oversized fixture row would otherwise make other tests' runs PARTIAL. Remove it.
            jdbc.sql("DELETE FROM news_item WHERE source_url = :url").param("url", oversizedUrl).update();
        }
    }
}
