package in.aitradingsystem.news;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class NewsSourceRegistryTest {
    @Test
    void moneycontrolUsesEnglishPublisherMarketRssAndAllowsOnlyPublisherArticleHosts() {
        NewsSource moneycontrol = NewsSource.byId("moneycontrol");

        assertThat(moneycontrol.feedUri().toString())
            .isEqualTo("https://www.moneycontrol.com/rss/marketreports.xml");
        assertThat(NewsUrlValidator.isAllowedArticleUrl(
            "https://www.moneycontrol.com/news/markets/sample.html", moneycontrol)).isTrue();
        assertThat(NewsUrlValidator.isAllowedArticleUrl(
            "https://attacker.example/news/markets/sample.html", moneycontrol)).isFalse();
    }

    @Test
    void nseAnnouncementsFeedAndArticleLinksAreRestrictedToOfficialHosts() {
        NewsSource nse = NewsSource.byId("nse-announcements");

        assertThat(nse.feedUri().toString())
            .isEqualTo("https://nsearchives.nseindia.com/content/RSS/Online_announcements.xml");
        assertThat(NewsUrlValidator.isAllowedArticleUrl(
            "https://nsearchives.nseindia.com/corporate/notice.pdf", nse)).isTrue();
        assertThat(NewsUrlValidator.isAllowedArticleUrl(
            "https://attacker.example/notice.pdf", nse)).isFalse();
    }
}
