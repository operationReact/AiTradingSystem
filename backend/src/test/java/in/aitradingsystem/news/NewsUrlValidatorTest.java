package in.aitradingsystem.news;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class NewsUrlValidatorTest {
    private final NewsSource source = NewsSource.byId("moneycontrol");

    @Test
    void acceptsApprovedHttpsPublisher() {
        assertTrue(NewsUrlValidator.isAllowedArticleUrl(
            "https://www.moneycontrol.com/news/markets/example", source));
    }

    @Test
    void rejectsUntrustedHostsAndSchemes() {
        assertFalse(NewsUrlValidator.isAllowedArticleUrl(
            "https://www.moneycontrol.com.evil.example/news", source));
        assertFalse(NewsUrlValidator.isAllowedArticleUrl(
            "http://www.moneycontrol.com/news", source));
        assertFalse(NewsUrlValidator.isAllowedArticleUrl(
            "https://evil.example@www.moneycontrol.com/news", source));
        assertFalse(NewsUrlValidator.isAllowedArticleUrl(
            "https://www.moneycontrol.com:8443/news", source));
    }

    @Test
    void rejectsMalformedOrMissingInputs() {
        assertFalse(NewsUrlValidator.isAllowedArticleUrl(null, source));
        assertFalse(NewsUrlValidator.isAllowedArticleUrl("not a url", source));
        assertFalse(NewsUrlValidator.isAllowedArticleUrl(
            "https://www.moneycontrol.com./news", source));
        assertFalse(NewsUrlValidator.isAllowedArticleUrl(
            "https://www.moneycontrol.com/news#fragment", source));
        assertFalse(NewsUrlValidator.isAllowedArticleUrl(
            "https://www.moneycontrol.com/news", null));
    }
}
