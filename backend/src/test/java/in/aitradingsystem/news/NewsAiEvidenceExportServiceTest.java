package in.aitradingsystem.news;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class NewsAiEvidenceExportServiceTest {
    private static final Instant FROM = Instant.parse("2026-10-01T09:00:00Z");
    private static final Instant TO = Instant.parse("2026-10-01T10:00:00Z");

    @Test
    void exportsEvidenceUsingGenerationTimeAsResearchCutoff() {
        NewsAiEvidenceRepository repository = mock(NewsAiEvidenceRepository.class);
        NewsAiEvidence first = evidence("analysis-1", Instant.parse("2026-10-01T09:15:30Z"));
        NewsAiEvidence second = evidence("analysis-2", Instant.parse("2026-10-01T09:20:30Z"));
        when(repository.researchEvidence(FROM, TO, 100)).thenReturn(List.of(first, second));

        NewsAiEvidenceExport export = new NewsAiEvidenceExportService(repository).export(FROM, TO, 100);

        assertThat(export.contractVersion()).isEqualTo("news-ai-evidence.v1");
        assertThat(export.dataCutoff()).isEqualTo(second.generatedAt());
        assertThat(export.providerSources()).containsExactly("GEMINI:gemini-2.5-flash");
        assertThat(export.evidence()).hasSize(2);
        assertThat(export.evidence().get(0).sourceAvailableAt()).isBefore(
            export.evidence().get(0).evidenceAvailableAt());
        assertThat(export.evidence().get(0).requestFingerprint()).hasSize(64);
    }

    @Test
    void rejectsInvalidWindowAndUnboundedLimit() {
        NewsAiEvidenceExportService service = new NewsAiEvidenceExportService(mock(NewsAiEvidenceRepository.class));

        assertThatThrownBy(() -> service.export(TO, FROM, 100))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("fromInclusive");
        assertThatThrownBy(() -> service.export(FROM, TO, 20_001))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("20000");
    }

    private static NewsAiEvidence evidence(String id, Instant generatedAt) {
        return new NewsAiEvidence(
            id,
            "news-1",
            "GEMINI",
            "gemini-2.5-flash",
            "interaction-1",
            generatedAt.minusSeconds(20),
            generatedAt,
            "a".repeat(64),
            "b".repeat(64),
            "c".repeat(64),
            "POSITIVE",
            new BigDecimal("0.550"),
            "EARNINGS",
            new BigDecimal("0.820"),
            new BigDecimal("0.770"),
            "MULTI_DAY",
            new BigDecimal("0.640"),
            false,
            true,
            List.of("RELIANCE"),
            "Profit improved.",
            List.of("Single-quarter evidence"),
            120,
            60,
            180,
            350);
    }
}
