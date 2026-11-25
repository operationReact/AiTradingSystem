package in.aitradingsystem.news;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import in.aitradingsystem.news.policy.NewsSourcePolicy;
import in.aitradingsystem.news.policy.NewsSourcePolicyService;
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
        NewsSourceHealthRepository health = Mockito.mock(NewsSourceHealthRepository.class);
        when(client.fetch(any(), anyInt())).thenReturn(new byte[] {1});
        List<NewsArticle> articles = List.of(article("1"), article("2"), article("3"));
        when(parser.parse(any(), any(), any(), anyInt())).thenReturn(articles);
        when(repository.insert(any())).thenReturn(Optional.of("id"));
        when(summarizer.summarize(any())).thenReturn(Optional.of(
            new GeminiNewsSummarizer.GeneratedSummary("GEMINI", "model", "summary", Instant.now())));

        var job = job(client, parser, repository, summarizer, health,
            List.of("moneycontrol"), List.of("TCS"), 1024, 100, 2, 1, 90);
        job.ingest();

        verify(parser).parse(any(), any(), any(), eq(2));
        verify(repository, times(2)).insert(any());
        verify(summarizer, times(1)).summarize(any());
        verify(repository, times(1)).saveSummary(any(), any(), any(), any(), any());
        verify(repository).deleteExpired(90);
        verify(health).record(
            eq("moneycontrol"), any(), any(), eq("SUCCESS"), eq(3), eq(2), eq(1),
            eq(null), eq(null));
    }

    @Test
    void providerFailureDoesNotAbortTheJobOrCreateArticles() throws Exception {
        RssFeedClient client = Mockito.mock(RssFeedClient.class);
        RssFeedParser parser = Mockito.mock(RssFeedParser.class);
        NewsRepository repository = Mockito.mock(NewsRepository.class);
        GeminiNewsSummarizer summarizer = Mockito.mock(GeminiNewsSummarizer.class);
        NewsSourceHealthRepository health = Mockito.mock(NewsSourceHealthRepository.class);
        when(client.fetch(any(), anyInt())).thenThrow(new java.io.IOException("provider down"));

        var job = job(client, parser, repository, summarizer, health,
            List.of("moneycontrol"), List.of(), 1024, 50, 100, 20, 90);
        job.ingest();

        verify(repository).deleteExpired(90);
        verify(repository, never()).insert(any());
        verify(health).record(
            eq("moneycontrol"), any(), any(), eq("FAILED"), eq(0), eq(0), eq(0),
            eq("IOException"), eq("provider down"));
    }

    @Test
    void failedFirstProviderDoesNotPreventSecondProviderFromIngesting() throws Exception {
        RssFeedClient client = Mockito.mock(RssFeedClient.class);
        RssFeedParser parser = Mockito.mock(RssFeedParser.class);
        NewsRepository repository = Mockito.mock(NewsRepository.class);
        GeminiNewsSummarizer summarizer = Mockito.mock(GeminiNewsSummarizer.class);
        NewsSourceHealthRepository health = Mockito.mock(NewsSourceHealthRepository.class);
        var first = NewsSource.byId("moneycontrol");
        var second = NewsSource.byId("economic-times");
        when(client.fetch(eq(first), anyInt()))
            .thenThrow(new java.io.IOException("first provider unavailable"));
        when(client.fetch(eq(second), anyInt()))
            .thenReturn(new byte[] {1});
        when(parser.parse(any(), eq(second), any(), anyInt()))
            .thenReturn(List.of(article("from-second-provider")));
        when(repository.insert(any())).thenReturn(Optional.of("inserted"));

        var job = job(client, parser, repository, summarizer, health,
            List.of("moneycontrol", "economic-times"), List.of(), 1024, 50, 100, 0, 90);
        job.ingest();

        verify(client).fetch(eq(first), anyInt());
        verify(client).fetch(eq(second), anyInt());
        verify(repository, times(1)).insert(any());
        verify(summarizer, never()).summarize(any());
        verify(health).record(
            eq("moneycontrol"), any(), any(), eq("FAILED"), eq(0), eq(0), eq(0),
            eq("IOException"), eq("first provider unavailable"));
        verify(health).record(
            eq("economic-times"), any(), any(), eq("SUCCESS"), eq(1), eq(1), eq(0),
            eq(null), eq(null));
    }

    @Test
    void duplicateArticleDoesNotConsumeSummaryBudget() throws Exception {
        RssFeedClient client = Mockito.mock(RssFeedClient.class);
        RssFeedParser parser = Mockito.mock(RssFeedParser.class);
        NewsRepository repository = Mockito.mock(NewsRepository.class);
        GeminiNewsSummarizer summarizer = Mockito.mock(GeminiNewsSummarizer.class);
        NewsSourceHealthRepository health = Mockito.mock(NewsSourceHealthRepository.class);
        when(client.fetch(any(), anyInt())).thenReturn(new byte[] {1});
        when(parser.parse(any(), any(), any(), anyInt()))
            .thenReturn(List.of(article("duplicate"), article("new")));
        when(repository.insert(any())).thenReturn(Optional.empty(), Optional.of("new-id"));
        when(summarizer.summarize(any())).thenReturn(Optional.of(
            new GeminiNewsSummarizer.GeneratedSummary("GEMINI", "model", "summary", Instant.now())));

        var job = job(client, parser, repository, summarizer, health,
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
        NewsSourceHealthRepository health = Mockito.mock(NewsSourceHealthRepository.class);
        var first = NewsSource.byId("moneycontrol");
        var second = NewsSource.byId("economic-times");
        when(client.fetch(any(), anyInt())).thenReturn(new byte[] {1});
        when(parser.parse(any(), eq(first), any(), anyInt()))
            .thenReturn(List.of(article("first"), article("second")));
        when(parser.parse(any(), eq(second), any(), anyInt()))
            .thenReturn(List.of(article("third")));
        when(repository.insert(any())).thenReturn(Optional.of("inserted"));
        when(summarizer.summarize(any()))
            .thenThrow(new IllegalStateException("optional model unavailable"));

        var job = job(client, parser, repository, summarizer, health,
            List.of("moneycontrol", "economic-times"), List.of(), 1024, 50, 3, 1, 90);
        job.ingest();

        verify(repository, times(3)).insert(any());
        verify(client).fetch(eq(second), anyInt());
        verify(summarizer, times(3)).summarize(any());
        verify(repository, never()).saveSummary(any(), any(), any(), any(), any());
    }

    @Test
    void retentionCleanupFailureDoesNotStopIngestion() throws Exception {
        RssFeedClient client = Mockito.mock(RssFeedClient.class);
        RssFeedParser parser = Mockito.mock(RssFeedParser.class);
        NewsRepository repository = Mockito.mock(NewsRepository.class);
        GeminiNewsSummarizer summarizer = Mockito.mock(GeminiNewsSummarizer.class);
        NewsSourceHealthRepository health = Mockito.mock(NewsSourceHealthRepository.class);
        Mockito.doThrow(new IllegalStateException("retention unavailable"))
            .when(repository).deleteExpired(90);
        when(client.fetch(any(), anyInt())).thenReturn(new byte[] {1});
        when(parser.parse(any(), any(), any(), anyInt()))
            .thenReturn(List.of(article("after-retention-error")));
        when(repository.insert(any())).thenReturn(Optional.of("inserted"));

        var job = job(client, parser, repository, summarizer, health,
            List.of("moneycontrol"), List.of(), 1024, 50, 100, 0, 90);
        job.ingest();

        verify(repository).deleteExpired(90);
        verify(repository, times(1)).insert(any());
        verify(health).record(
            eq("moneycontrol"), any(), any(), eq("SUCCESS"), eq(1), eq(1), eq(0),
            eq(null), eq(null));
    }


    private static NewsIngestionJob job(RssFeedClient client, RssFeedParser parser, NewsRepository repository,
        GeminiNewsSummarizer summarizer, NewsSourceHealthRepository health,
        List<String> sources, List<String> watchlist, int payloadBytes, int itemsPerSource, int articlesPerRun,
        int summariesPerRun, int retentionDays) {
        // Explicitly permissive fixture policy; never used in an actual configured source registry.
        NewsSourcePolicyService policy = Mockito.mock(NewsSourcePolicyService.class);
        when(policy.decide(any(), any(), any())).thenReturn(NewsSourcePolicy.Decision.PERMIT);
        when(policy.permitted(any(), any(), any())).thenReturn(true);
        return new NewsIngestionJob(client, parser, repository, summarizer, health, policy, sources, watchlist,
            payloadBytes, itemsPerSource, articlesPerRun, summariesPerRun, retentionDays);
    }

    @Test
    void missingIngestRightsPreventsNetworkFetchAndStorage() throws Exception {
        RssFeedClient client = Mockito.mock(RssFeedClient.class);
        RssFeedParser parser = Mockito.mock(RssFeedParser.class);
        NewsRepository repository = Mockito.mock(NewsRepository.class);
        GeminiNewsSummarizer summarizer = Mockito.mock(GeminiNewsSummarizer.class);
        NewsSourceHealthRepository health = Mockito.mock(NewsSourceHealthRepository.class);
        NewsSourcePolicyService policy = Mockito.mock(NewsSourcePolicyService.class);
        when(policy.decide(eq("moneycontrol"), eq(NewsSourcePolicy.Use.INGEST), any()))
            .thenReturn(NewsSourcePolicy.Decision.DENY_REVIEW_PENDING);

        new NewsIngestionJob(client, parser, repository, summarizer, health, policy,
            List.of("moneycontrol"), List.of(), 1024, 50, 100, 20, 90).ingest();

        verify(client, never()).fetch(any(), anyInt());
        verify(repository, never()).insert(any());
        verify(summarizer, never()).summarize(any());
        verify(health).record(eq("moneycontrol"), any(), any(), eq("FAILED"), eq(0), eq(0), eq(0),
            eq("RIGHTS_INGEST_BLOCKED"), eq("DENY_REVIEW_PENDING"));
    }

    @Test
    void missingRetentionRightsPreventsNetworkFetchAndStorage() throws Exception {
        RssFeedClient client = Mockito.mock(RssFeedClient.class);
        RssFeedParser parser = Mockito.mock(RssFeedParser.class);
        NewsRepository repository = Mockito.mock(NewsRepository.class);
        GeminiNewsSummarizer summarizer = Mockito.mock(GeminiNewsSummarizer.class);
        NewsSourceHealthRepository health = Mockito.mock(NewsSourceHealthRepository.class);
        NewsSourcePolicyService policy = Mockito.mock(NewsSourcePolicyService.class);
        when(policy.decide(eq("moneycontrol"), eq(NewsSourcePolicy.Use.INGEST), any()))
            .thenReturn(NewsSourcePolicy.Decision.PERMIT);
        when(policy.decide(eq("moneycontrol"), eq(NewsSourcePolicy.Use.RETAIN), any()))
            .thenReturn(NewsSourcePolicy.Decision.DENY_USE_NOT_PERMITTED);

        new NewsIngestionJob(client, parser, repository, summarizer, health, policy,
            List.of("moneycontrol"), List.of(), 1024, 50, 100, 20, 90).ingest();

        verify(client, never()).fetch(any(), anyInt());
        verify(repository, never()).insert(any());
        verify(health).record(eq("moneycontrol"), any(), any(), eq("FAILED"), eq(0), eq(0), eq(0),
            eq("RIGHTS_RETENTION_BLOCKED"), eq("DENY_USE_NOT_PERMITTED"));
    }

    @Test
    void ingestAndRetainRightsDoNotImplyGeminiTransferRights() throws Exception {
        RssFeedClient client = Mockito.mock(RssFeedClient.class);
        RssFeedParser parser = Mockito.mock(RssFeedParser.class);
        NewsRepository repository = Mockito.mock(NewsRepository.class);
        GeminiNewsSummarizer summarizer = Mockito.mock(GeminiNewsSummarizer.class);
        NewsSourceHealthRepository health = Mockito.mock(NewsSourceHealthRepository.class);
        NewsSourcePolicyService policy = Mockito.mock(NewsSourcePolicyService.class);
        when(policy.decide(any(), any(), any())).thenReturn(NewsSourcePolicy.Decision.PERMIT);
        when(policy.permitted(eq("moneycontrol"), eq(NewsSourcePolicy.Use.INGEST), any())).thenReturn(true);
        when(policy.permitted(eq("moneycontrol"), eq(NewsSourcePolicy.Use.RETAIN), any())).thenReturn(true);
        when(policy.permitted(eq("moneycontrol"), eq(NewsSourcePolicy.Use.NLP_TRANSFER), any())).thenReturn(false);
        when(client.fetch(any(), anyInt())).thenReturn(new byte[] {1});
        when(parser.parse(any(), any(), any(), anyInt())).thenReturn(List.of(article("approved-ingest")));
        when(repository.insert(any())).thenReturn(Optional.of("new-id"));

        new NewsIngestionJob(client, parser, repository, summarizer, health, policy,
            List.of("moneycontrol"), List.of(), 1024, 50, 100, 20, 90).ingest();

        verify(client).fetch(any(), anyInt());
        verify(repository).insert(any());
        verify(summarizer, never()).summarize(any());
        verify(repository, never()).saveSummary(any(), any(), any(), any(), any());
        verify(health).record(eq("moneycontrol"), any(), any(), eq("SUCCESS"), eq(1), eq(1), eq(0),
            eq(null), eq(null));
    }

    private static NewsArticle article(String id) {
        return new NewsArticle("moneycontrol", "Moneycontrol",
            "https://www.moneycontrol.com/news/" + id, Instant.now(), "TCS headline " + id,
            "TCS content " + id, NewsText.hash("TCS headline " + id, "TCS content " + id), List.of("TCS"));
    }
}
