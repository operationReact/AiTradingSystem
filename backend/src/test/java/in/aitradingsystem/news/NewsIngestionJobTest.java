package in.aitradingsystem.news;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

class NewsIngestionJobTest {
    @Test
    void ingestionBoundsArticlesAndOptionalSummariesPerRun() throws Exception {
        RssFeedClient client = Mockito.mock(RssFeedClient.class);
        RssFeedParser parser = Mockito.mock(RssFeedParser.class);
        NewsRepository repository = Mockito.mock(NewsRepository.class);
        GeminiNewsSummarizer summarizer = Mockito.mock(GeminiNewsSummarizer.class);
        when(client.fetch(any(), anyInt())).thenReturn(new byte[] {1});
        List<NewsArticle> articles = List.of(article("1"), article("2"), article("3"));
        when(parser.parse(any(), any(), any(), anyInt())).thenReturn(articles);
        when(repository.insert(any())).thenReturn(Optional.of("id"));
        when(summarizer.summarize(any())).thenReturn(Optional.of(
            new GeminiNewsSummarizer.GeneratedSummary("GEMINI", "model", "summary", Instant.now())));

        var job = new NewsIngestionJob(client, parser, repository, summarizer, List.of("moneycontrol"),
            List.of("TCS"), 1024, 100, 2, 1, 90);
        job.ingest();

        verify(parser).parse(any(), any(), any(), org.mockito.ArgumentMatchers.eq(2));
        verify(repository, times(2)).insert(any());
        verify(summarizer, times(1)).summarize(any());
        verify(repository, times(1)).saveSummary(any(), any(), any(), any(), any());
        verify(repository).deleteExpired(90);
    }

    @Test
    void providerFailureDoesNotAbortTheJobOrCreateArticles() throws Exception {
        RssFeedClient client = Mockito.mock(RssFeedClient.class);
        RssFeedParser parser = Mockito.mock(RssFeedParser.class);
        NewsRepository repository = Mockito.mock(NewsRepository.class);
        GeminiNewsSummarizer summarizer = Mockito.mock(GeminiNewsSummarizer.class);
        when(client.fetch(any(), anyInt())).thenThrow(new java.io.IOException("provider down"));

        var job = new NewsIngestionJob(client, parser, repository, summarizer, List.of("moneycontrol"),
            List.of(), 1024, 50, 100, 20, 90);
        job.ingest();

        verify(repository).deleteExpired(90);
        verify(repository, never()).insert(any());
    }

    @Test
    void failedFirstProviderDoesNotPreventSecondProviderFromIngesting() throws Exception {
        RssFeedClient client = Mockito.mock(RssFeedClient.class);
        RssFeedParser parser = Mockito.mock(RssFeedParser.class);
        NewsRepository repository = Mockito.mock(NewsRepository.class);
        GeminiNewsSummarizer summarizer = Mockito.mock(GeminiNewsSummarizer.class);
        var first = NewsSource.byId("moneycontrol");
        var second = NewsSource.byId("economic-times");
        when(client.fetch(org.mockito.ArgumentMatchers.eq(first), anyInt()))
            .thenThrow(new java.io.IOException("first provider unavailable"));
        when(client.fetch(org.mockito.ArgumentMatchers.eq(second), anyInt()))
            .thenReturn(new byte[] {1});
        when(parser.parse(any(), org.mockito.ArgumentMatchers.eq(second), any(), anyInt()))
            .thenReturn(List.of(article("from-second-provider")));
        when(repository.insert(any())).thenReturn(Optional.of("inserted"));

        var job = new NewsIngestionJob(client, parser, repository, summarizer,
            List.of("moneycontrol", "economic-times"), List.of(), 1024, 50, 100, 0, 90);
        job.ingest();

        verify(client).fetch(org.mockito.ArgumentMatchers.eq(first), anyInt());
        verify(client).fetch(org.mockito.ArgumentMatchers.eq(second), anyInt());
        verify(repository, times(1)).insert(any());
        verify(summarizer, never()).summarize(any());
    }

    @Test
    void duplicateArticleDoesNotConsumeSummaryBudget() throws Exception {
        RssFeedClient client = Mockito.mock(RssFeedClient.class);
        RssFeedParser parser = Mockito.mock(RssFeedParser.class);
        NewsRepository repository = Mockito.mock(NewsRepository.class);
        GeminiNewsSummarizer summarizer = Mockito.mock(GeminiNewsSummarizer.class);
        when(client.fetch(any(), anyInt())).thenReturn(new byte[] {1});
        when(parser.parse(any(), any(), any(), anyInt()))
            .thenReturn(List.of(article("duplicate"), article("new")));
        when(repository.insert(any())).thenReturn(Optional.empty(), Optional.of("new-id"));
        when(summarizer.summarize(any())).thenReturn(Optional.of(
            new GeminiNewsSummarizer.GeneratedSummary("GEMINI", "model", "summary", Instant.now())));

        var job = new NewsIngestionJob(client, parser, repository, summarizer,
            List.of("moneycontrol"), List.of(), 1024, 50, 100, 1, 90);
        job.ingest();

        verify(repository, times(2)).insert(any());
        verify(summarizer, times(1)).summarize(any());
        verify(repository, times(1)).saveSummary(any(), any(), any(), any(), any());
    }

    @Test
    void optionalSummaryFailureDoesNotBlockSubsequentArticleOrProvider() throws Exception {
        RssFeedClient client = Mockito.mock(RssFeedClient.class);
        RssFeedParser parser = Mockito.mock(RssFeedParser.class);
        NewsRepository repository = Mockito.mock(NewsRepository.class);
        GeminiNewsSummarizer summarizer = Mockito.mock(GeminiNewsSummarizer.class);
        var first = NewsSource.byId("moneycontrol");
        var second = NewsSource.byId("economic-times");
        when(client.fetch(any(), anyInt())).thenReturn(new byte[] {1});
        when(parser.parse(any(), org.mockito.ArgumentMatchers.eq(first), any(), anyInt()))
            .thenReturn(List.of(article("first"), article("second")));
        when(parser.parse(any(), org.mockito.ArgumentMatchers.eq(second), any(), anyInt()))
            .thenReturn(List.of(article("third")));
        when(repository.insert(any())).thenReturn(Optional.of("inserted"));
        when(summarizer.summarize(any()))
            .thenThrow(new IllegalStateException("optional model unavailable"));

        var job = new NewsIngestionJob(client, parser, repository, summarizer,
            List.of("moneycontrol", "economic-times"), List.of(), 1024, 50, 3, 1, 90);
        job.ingest();

        verify(repository, times(3)).insert(any());
        verify(client).fetch(org.mockito.ArgumentMatchers.eq(second), anyInt());
        verify(summarizer, times(3)).summarize(any());
        verify(repository, never()).saveSummary(any(), any(), any(), any(), any());
    }

    private static NewsArticle article(String id) {
        return new NewsArticle("moneycontrol", "Moneycontrol",
            "https://www.moneycontrol.com/news/" + id, Instant.now(), "TCS headline " + id,
            "TCS content " + id, NewsText.hash("TCS headline " + id, "TCS content " + id), List.of("TCS"));
    }
}
