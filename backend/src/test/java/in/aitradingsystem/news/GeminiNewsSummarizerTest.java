package in.aitradingsystem.news;

import static org.junit.jupiter.api.Assertions.assertTrue;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class GeminiNewsSummarizerTest {
    @Test
    void explicitOptInIsRequiredEvenWithApiKey() throws Exception {
        var summarizer = new GeminiNewsSummarizer(new ObjectMapper(),
            "test-key-that-must-not-be-used", "gemini-2.5-flash", false);
        assertTrue(summarizer.summarize(article()).isEmpty());
    }

    @Test
    void blankApiKeyDisablesSummarization() throws Exception {
        var summarizer = new GeminiNewsSummarizer(new ObjectMapper(),
            "   ", "gemini-2.5-flash", true);
        assertTrue(summarizer.summarize(article()).isEmpty());
    }

    private static NewsArticle article() {
        return new NewsArticle("moneycontrol", "Moneycontrol",
            "https://www.moneycontrol.com/news/example", Instant.now(), "Example", "Article text",
            NewsText.hash("Example", "Article text"), List.of());
    }

    @Test
    void missingApiKeyDisablesSummarizationEvenWhenEnabled() throws Exception {
        var summarizer = new GeminiNewsSummarizer(new ObjectMapper(), "", "gemini-2.5-flash", true);
        var article = new NewsArticle("moneycontrol", "Moneycontrol",
            "https://www.moneycontrol.com/news/example", Instant.now(), "Example", "Article text",
            NewsText.hash("Example", "Article text"), List.of());
        assertTrue(summarizer.summarize(article).isEmpty());
    }
}
