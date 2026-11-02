package in.aitradingsystem.news;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import in.aitradingsystem.config.NewsProperties;
import in.aitradingsystem.domain.NewsItem;
import in.aitradingsystem.news.analysis.NewsAnalysisParser;
import in.aitradingsystem.news.analysis.NewsAnalysisProvider;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class NewsResearchAiEvidenceServiceTest {
    private static final String RESPONSE = """
        {"sentiment":"POSITIVE","directionScore":0.55,"category":"EARNINGS","confidence":0.82,
         "materiality":0.77,"horizon":"MULTI_DAY","novelty":0.64,
         "contradiction":false,"ambiguity":true,
         "impactedSymbols":["RELIANCE","HALLUCINATED"],
         "evidence":"Profit and revenue improved.","risks":["Single-quarter evidence"]}
        """;

    @Test
    void storesOnlyAuthoritativelyLinkedSymbolsAndProviderUsage() {
        NewsAiEvidenceRepository repository = mock(NewsAiEvidenceRepository.class);
        NewsAnalysisProvider provider = provider();
        when(provider.complete(anyString(), anyString())).thenReturn(
            new NewsAnalysisProvider.Completion(RESPONSE, "interaction-1", 120, 60, 180));
        NewsResearchAiEvidenceService service = service(repository, provider);

        assertThat(service.analyzeIfNeeded(item())).isTrue();

        ArgumentCaptor<NewsAiEvidence> captor = ArgumentCaptor.forClass(NewsAiEvidence.class);
        verify(repository).save(captor.capture());
        NewsAiEvidence saved = captor.getValue();
        assertThat(saved.newsId()).isEqualTo("11111111-1111-1111-1111-111111111111");
        assertThat(saved.impactedSymbols()).containsExactly("RELIANCE");
        assertThat(saved.sourceAvailableAt()).isEqualTo(item().fetchedAt());
        assertThat(saved.generatedAt()).isAfterOrEqualTo(saved.sourceAvailableAt());
        assertThat(saved.providerRequestId()).isEqualTo("interaction-1");
        assertThat(saved.inputTokens()).isEqualTo(120);
        assertThat(saved.outputTokens()).isEqualTo(60);
        assertThat(saved.totalTokens()).isEqualTo(180);
        assertThat(saved.promptHash()).matches("[a-f0-9]{64}");
        assertThat(saved.requestFingerprint()).matches("[a-f0-9]{64}");
        assertThat(saved.responseFingerprint()).matches("[a-f0-9]{64}");
    }

    @Test
    void skipsProviderCallWhenRequestFingerprintAlreadyExists() {
        NewsAiEvidenceRepository repository = mock(NewsAiEvidenceRepository.class);
        NewsAnalysisProvider provider = provider();
        when(repository.existsByRequestFingerprint(anyString())).thenReturn(true);
        NewsResearchAiEvidenceService service = service(repository, provider);

        assertThat(service.analyzeIfNeeded(item())).isFalse();

        verify(provider, never()).complete(anyString(), anyString());
        verify(repository, never()).save(org.mockito.ArgumentMatchers.any());
    }

    private static NewsResearchAiEvidenceService service(
        NewsAiEvidenceRepository repository,
        NewsAnalysisProvider provider) {
        NewsProperties props = new NewsProperties(
            "GEMINI", "gemini-2.5-flash", "claude-test", "test-key", "", 1000, 5000, 2);
        return new NewsResearchAiEvidenceService(
            repository,
            props,
            List.of(provider),
            new NewsAnalysisParser(new ObjectMapper()));
    }

    private static NewsAnalysisProvider provider() {
        NewsAnalysisProvider provider = mock(NewsAnalysisProvider.class);
        when(provider.name()).thenReturn("GEMINI");
        when(provider.model()).thenReturn("gemini-2.5-flash");
        when(provider.configured()).thenReturn(true);
        return provider;
    }

    private static NewsItem item() {
        return new NewsItem(
            "11111111-1111-1111-1111-111111111111",
            "exchange-news",
            "Exchange News",
            "https://example.test/news-1",
            Instant.parse("2026-10-01T09:16:20Z"),
            Instant.parse("2026-10-01T09:16:30Z"),
            "Quarterly result",
            "Reliance reported improved profit and revenue.",
            "hash-1",
            List.of("RELIANCE"),
            "INGESTED",
            null,
            null,
            null,
            null);
    }
}
