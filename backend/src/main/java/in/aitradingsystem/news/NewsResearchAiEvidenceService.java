package in.aitradingsystem.news;

import in.aitradingsystem.config.NewsProperties;
import in.aitradingsystem.domain.NewsItem;
import in.aitradingsystem.news.analysis.AiErrorCode;
import in.aitradingsystem.news.analysis.AiProviderException;
import in.aitradingsystem.news.analysis.NewsAnalysisParser;
import in.aitradingsystem.news.analysis.NewsAnalysisProvider;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * Produces cached, schema-validated AI evidence for the authoritative provenance-aware news feed.
 * Evidence remains advisory and can never write to orders, positions or risk decisions.
 */
@Service
public class NewsResearchAiEvidenceService {
    private static final int MAX_ARTICLE_CHARS = 6000;

    private final NewsAiEvidenceRepository evidenceRepository;
    private final NewsProperties props;
    private final Map<String, NewsAnalysisProvider> providers;
    private final NewsAnalysisParser parser;

    public NewsResearchAiEvidenceService(
        NewsAiEvidenceRepository evidenceRepository,
        NewsProperties props,
        List<NewsAnalysisProvider> providers,
        NewsAnalysisParser parser) {
        this.evidenceRepository = evidenceRepository;
        this.props = props;
        this.providers = providers.stream().collect(
            java.util.stream.Collectors.toMap(NewsAnalysisProvider::name, provider -> provider));
        this.parser = parser;
    }

    /** Returns true only when a new provider call was made and new evidence was stored. */
    public boolean analyzeIfNeeded(NewsItem item) {
        if (item == null || item.linkedSymbols() == null || item.linkedSymbols().isEmpty()) return false;

        NewsAnalysisProvider provider = providers.get(props.provider());
        if (provider == null || !provider.configured()) {
            throw new AiProviderException(AiErrorCode.UNCONFIGURED, "No AI news provider is configured");
        }

        String userPrompt = userPrompt(item);
        String promptHash = NewsAnalysisService.sha256(NewsAnalysisService.SYSTEM_PROMPT);
        String requestFingerprint = NewsAnalysisService.sha256(
            provider.name() + "\n" + provider.model() + "\n" + promptHash + "\n" + userPrompt);
        if (evidenceRepository.existsByRequestFingerprint(requestFingerprint)) return false;

        long started = System.nanoTime();
        AiProviderException last = null;
        for (int attempt = 1; attempt <= props.maxAttempts(); attempt++) {
            try {
                NewsAnalysisProvider.Completion completion =
                    provider.complete(NewsAnalysisService.SYSTEM_PROMPT, userPrompt);
                NewsAnalysisParser.Result result = parser.parse(completion.text());
                Instant generatedAt = Instant.now();
                if (item.fetchedAt().isAfter(generatedAt)) {
                    throw new AiProviderException(
                        AiErrorCode.INVALID_RESPONSE,
                        "News source availability cannot be in the future");
                }
                List<String> groundedSymbols = result.impactedSymbols().stream()
                    .filter(item.linkedSymbols()::contains)
                    .distinct()
                    .sorted()
                    .toList();
                evidenceRepository.save(new NewsAiEvidence(
                    UUID.randomUUID().toString(),
                    item.id(),
                    provider.name(),
                    provider.model(),
                    completion.providerRequestId(),
                    item.fetchedAt(),
                    generatedAt,
                    promptHash,
                    requestFingerprint,
                    NewsAnalysisService.sha256(completion.text()),
                    result.sentiment(),
                    result.directionScore(),
                    result.category(),
                    result.confidence(),
                    result.materiality(),
                    result.horizon(),
                    result.novelty(),
                    result.contradiction(),
                    result.ambiguity(),
                    groundedSymbols,
                    result.evidence(),
                    result.risks(),
                    completion.inputTokens(),
                    completion.outputTokens(),
                    completion.totalTokens(),
                    elapsedMs(started)));
                return true;
            } catch (AiProviderException e) {
                last = e;
                if (!e.retryable()) break;
            }
        }
        if (last != null) throw last;
        throw new AiProviderException(AiErrorCode.INVALID_RESPONSE, "AI evidence analysis failed");
    }

    static String userPrompt(NewsItem item) {
        String body = item.normalizedContent() == null ? "" : item.normalizedContent();
        if (body.length() > MAX_ARTICLE_CHARS) body = body.substring(0, MAX_ARTICLE_CHARS);
        return """
            <article>
            sourceId: %s
            source: %s
            publisher: %s
            publishedAt: %s
            sourceAvailableAt: %s
            linkedSymbols: %s
            title: %s
            body: %s
            </article>
            """.formatted(
                item.id(),
                item.source(),
                item.publisher(),
                item.publishedAt(),
                item.fetchedAt(),
                item.linkedSymbols(),
                item.title(),
                body);
    }

    private static int elapsedMs(long startNanos) {
        return (int) Math.min(Integer.MAX_VALUE, (System.nanoTime() - startNanos) / 1_000_000);
    }
}
