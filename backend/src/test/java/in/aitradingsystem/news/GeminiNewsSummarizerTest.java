package in.aitradingsystem.news;

import static org.junit.jupiter.api.Assertions.assertTrue;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class GeminiNewsSummarizerTest {
    @Test
    void missingApiKeyDisablesSummarizationEvenWhenEnabled() throws Exception {
        var summarizer = new GeminiNewsSummarizer(new ObjectMapper(), "", "gemini-2.5-flash", true);
        var article = new NewsArticle("moneycontrol", "Moneycontrol",
            "https://www.moneycontrol.com/news/example", Instant.now(), "Example", "Article text",
            NewsText.hash("Example", "Article text"), List.of());
        assertTrue(summarizer.summarize(article).isEmpty());
    }
}
