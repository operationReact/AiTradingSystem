package in.aitradingsystem.news;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import org.springframework.stereotype.Service;

@Service
public class NewsAiEvidenceExportService {
    static final String CONTRACT_VERSION = "news-ai-evidence.v1";
    private static final int MAX_ROWS = 20_000;

    private final NewsAiEvidenceRepository repository;

    public NewsAiEvidenceExportService(NewsAiEvidenceRepository repository) {
        this.repository = repository;
    }

    public NewsAiEvidenceExport export(Instant fromInclusive, Instant toExclusive, int limit) {
        if (fromInclusive == null || toExclusive == null || !fromInclusive.isBefore(toExclusive)) {
            throw new IllegalArgumentException("fromInclusive must be before toExclusive");
        }
        if (limit < 1 || limit > MAX_ROWS) {
            throw new IllegalArgumentException("AI evidence limit must be between 1 and " + MAX_ROWS);
        }

        List<NewsAiEvidence> rows = repository.researchEvidence(fromInclusive, toExclusive, limit);
        List<NewsAiEvidenceExport.ResearchAiEvidence> evidence = rows.stream()
            .map(row -> new NewsAiEvidenceExport.ResearchAiEvidence(
                row.id(),
                row.newsId(),
                row.provider(),
                row.model(),
                row.providerRequestId(),
                row.sourceAvailableAt(),
                row.generatedAt(),
                row.promptHash(),
                row.requestFingerprint(),
                row.responseFingerprint(),
                row.sentiment(),
                row.directionScore(),
                row.category(),
                row.confidence(),
                row.materiality(),
                row.horizon(),
                row.novelty(),
                row.contradiction(),
                row.ambiguity(),
                row.impactedSymbols(),
                row.inputTokens(),
                row.outputTokens(),
                row.totalTokens()))
            .toList();
        Instant dataCutoff = evidence.stream()
            .map(NewsAiEvidenceExport.ResearchAiEvidence::evidenceAvailableAt)
            .max(Comparator.naturalOrder())
            .orElse(null);
        List<String> providers = evidence.stream()
            .map(row -> row.provider() + ":" + row.model())
            .distinct()
            .sorted()
            .toList();
        return new NewsAiEvidenceExport(
            CONTRACT_VERSION,
            Instant.now(),
            fromInclusive,
            toExclusive,
            dataCutoff,
            providers,
            evidence);
    }
}
