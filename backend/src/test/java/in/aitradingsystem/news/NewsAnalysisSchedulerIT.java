package in.aitradingsystem.news;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import in.aitradingsystem.config.NewsProperties;
import in.aitradingsystem.news.analysis.AiErrorCode;
import in.aitradingsystem.news.analysis.AiProviderException;
import in.aitradingsystem.news.analysis.NewsAnalysisParser;
import in.aitradingsystem.news.analysis.NewsAnalysisProvider;
import in.aitradingsystem.support.AbstractPostgresIT;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Scheduler over the real NewsAnalysisService and parser, with a fake provider. The article text is
 * fixture data; these tests prove scheduling, settlement, backoff and the attempt cap. They are not
 * evidence about real news or about any model's quality.
 */
class NewsAnalysisSchedulerIT extends AbstractPostgresIT {
    private static final String VALID = """
        {"sentiment":"POSITIVE","directionScore":0.6,"category":"EARNINGS","confidence":0.8,
         "materiality":0.7,"horizon":"INTRADAY","novelty":0.5,"contradiction":false,"ambiguity":false,
         "impactedSymbols":["RELIANCE"],"evidence":"Fixture paraphrase","risks":[]}
        """;

    @Autowired JdbcClient jdbc;
    @Autowired NewsService news;
    @Autowired ObjectMapper mapper;
    @Autowired in.aitradingsystem.news.policy.NewsSourcePolicyService policy;

    static final class FakeProvider implements NewsAnalysisProvider {
        final boolean configured;
        final boolean fail;
        final AtomicInteger calls = new AtomicInteger();

        FakeProvider(boolean configured, boolean fail) {
            this.configured = configured;
            this.fail = fail;
        }

        @Override public String name() { return "FAKE"; }
        @Override public String model() { return "fake-fixture-1"; }
        @Override public boolean configured() { return configured; }

        @Override
        public Completion complete(String systemPrompt, String userPrompt) {
            calls.incrementAndGet();
            if (fail) throw new AiProviderException(AiErrorCode.INVALID_RESPONSE, "fixture failure");
            return new Completion(VALID, "req-fixture");
        }
    }

    private NewsAnalysisService analysis(FakeProvider provider) {
        return new NewsAnalysisService(jdbc, mapper,
            new NewsProperties("FAKE", "m", "m", null, null, 5000, 30000, 2),
            List.of(provider), new NewsAnalysisParser(mapper), news);
    }

    private UUID ingestArticle(Instant publishedAt) {
        return ingestArticle(publishedAt, "fixture-source");
    }

    private UUID ingestArticle(Instant publishedAt, String source) {
        String url = "https://news.example.test/scheduler/" + UUID.randomUUID();
        news.ingest(new NewsService.IngestRequest(List.of(new NewsService.ArticleInput(
            source, null, "Fixture headline", url, "Fixture excerpt", publishedAt))));
        return jdbc.sql("SELECT id FROM news_articles WHERE url = :url")
            .param("url", url).query(UUID.class).single();
    }

    private long analysisRows(UUID articleId) {
        return jdbc.sql("SELECT count(*) FROM news_analyses WHERE article_id = :id")
            .param("id", articleId).query(Long.class).single();
    }

    private NewsAnalysisScheduler scheduler(NewsAnalysisService service, Instant at) {
        return scheduler(service, at, List.of("fixture-source"));
    }

    private NewsAnalysisScheduler scheduler(NewsAnalysisService service, Instant at, List<String> approved) {
        for (String source : approved) {
            in.aitradingsystem.news.policy.NewsSourcePolicyTestSupport.approveFixture(jdbc, source);
        }
        return new NewsAnalysisScheduler(jdbc, service, Clock.fixed(at, ZoneOffset.UTC), 20, approved, policy);
    }

    @Test
    void unconfiguredProviderNeverCallsAndCreatesNoAnalysis() {
        UUID article = ingestArticle(Instant.now().minusSeconds(120));
        FakeProvider provider = new FakeProvider(false, false);

        Map<NewsAnalysisScheduler.Outcome, Integer> counts =
            scheduler(analysis(provider), Instant.now()).runOnce();

        assertThat(counts).containsKey(NewsAnalysisScheduler.Outcome.CONFIGURATION_BLOCKED);
        assertThat(provider.calls.get()).isZero();
        assertThat(analysisRows(article)).isZero();
    }

    @Test
    void settledArticleIsAnalyzedOnceAndNotRecomputed() {
        UUID article = ingestArticle(Instant.now().minusSeconds(120));
        FakeProvider provider = new FakeProvider(true, false);

        scheduler(analysis(provider), Instant.now()).runOnce();
        assertThat(analysisRows(article)).isEqualTo(1);
        String status = jdbc.sql("SELECT status FROM news_analyses WHERE article_id = :id")
            .param("id", article).query(String.class).single();
        assertThat(status).isEqualTo("ANALYZED");

        int callsAfterFirst = provider.calls.get();
        scheduler(analysis(provider), Instant.now()).runOnce();
        assertThat(analysisRows(article)).isEqualTo(1);
        assertThat(provider.calls.get()).isEqualTo(callsAfterFirst);
    }

    @Test
    void failedAnalysisRetriesOnlyAfterBackoffAndStopsAtTheAttemptCap() {
        UUID article = ingestArticle(Instant.now().minusSeconds(120));
        FakeProvider provider = new FakeProvider(true, true);
        Instant t0 = Instant.now();
        NewsAnalysisService service = analysis(provider);

        // Attempt 1 settles as FAILED at t0. Immediately afterwards it is inside the backoff.
        scheduler(service, t0).runOnce();
        assertThat(analysisRows(article)).isEqualTo(1);
        scheduler(service, t0.plusSeconds(60)).runOnce();
        assertThat(analysisRows(article)).isEqualTo(1);

        // After the backoff it is retried, twice more, then never again.
        scheduler(service, t0.plusSeconds(2 * 3600)).runOnce();
        assertThat(analysisRows(article)).isEqualTo(2);
        scheduler(service, t0.plusSeconds(4 * 3600)).runOnce();
        assertThat(analysisRows(article)).isEqualTo(3);
        scheduler(service, t0.plusSeconds(8 * 3600)).runOnce();
        assertThat(analysisRows(article)).isEqualTo(3);

        long failed = jdbc.sql("SELECT count(*) FROM news_analyses WHERE article_id = :id AND status = 'FAILED'")
            .param("id", article).query(Long.class).single();
        assertThat(failed).isEqualTo(3);
    }
    @Test
    void noApprovedSourceBlocksAnalysisEvenWithConfiguredProvider() {
        UUID article = ingestArticle(Instant.now().minusSeconds(120));
        FakeProvider provider = new FakeProvider(true, false);

        Map<NewsAnalysisScheduler.Outcome, Integer> result =
            scheduler(analysis(provider), Instant.now(), List.of()).runOnce();

        assertThat(result).containsEntry(NewsAnalysisScheduler.Outcome.SOURCE_APPROVAL_BLOCKED, 1);
        assertThat(provider.calls.get()).isZero();
        assertThat(analysisRows(article)).isZero();
    }

    @Test
    void storedArticleFromUnapprovedSourceIsNeverAnalyzed() {
        UUID unapproved = ingestArticle(Instant.now().minusSeconds(120), "not-approved");
        FakeProvider provider = new FakeProvider(true, false);

        scheduler(analysis(provider), Instant.now()).runOnce();

        assertThat(provider.calls.get()).isZero();
        assertThat(analysisRows(unapproved)).isZero();
    }

    /** Revocation stops automated analysis before any provider call (MI-PR02 rights gate). */
    @Test
    void revokedSourceStopsAutomatedAnalysisBeforeAnyProviderCall() {
        String source = "rights-" + UUID.randomUUID();
        UUID article = ingestArticle(Instant.now().minusSeconds(120), source);
        FakeProvider provider = new FakeProvider(true, false);
        NewsAnalysisScheduler scheduler = scheduler(analysis(provider), Instant.now(), List.of(source));
        jdbc.sql("UPDATE news_source_policy SET license_state = 'REVOKED' WHERE source_id = :id")
            .param("id", source).update();

        Map<NewsAnalysisScheduler.Outcome, Integer> result = scheduler.runOnce();

        assertThat(result).containsEntry(NewsAnalysisScheduler.Outcome.RIGHTS_BLOCKED, 1);
        assertThat(provider.calls.get()).isZero();
        assertThat(analysisRows(article)).isZero();
    }
}
