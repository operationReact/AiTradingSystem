package in.aitradingsystem.news;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import in.aitradingsystem.domain.NewsItem;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class NewsResearchExportServiceTest {
    private static final Instant FROM = Instant.parse("2026-10-01T00:00:00Z");
    private static final Instant TO = Instant.parse("2026-10-02T00:00:00Z");

    @Test
    void exportsAvailabilityFromFetchedAtAndPreservesSourceIdentity() {
        NewsRepository repository = mock(NewsRepository.class);
        NewsItem older = item(
            "11111111-1111-1111-1111-111111111111",
            "moneycontrol",
            Instant.parse("2026-10-01T09:00:00Z"),
            Instant.parse("2026-10-01T09:02:00Z"),
            "hash-a",
            List.of("RELIANCE"));
        NewsItem newer = item(
            "22222222-2222-2222-2222-222222222222",
            "economic-times",
            Instant.parse("2026-10-01T10:00:00Z"),
            Instant.parse("2026-10-01T10:04:00Z"),
            "hash-b",
            List.of("TCS", "INFY"));
        when(repository.researchEvents(FROM, TO, 100)).thenReturn(List.of(older, newer));

        NewsResearchExport export = new NewsResearchExportService(repository).export(FROM, TO, 100);

        assertThat(export.contractVersion()).isEqualTo("news-events.v1");
        assertThat(export.fromInclusive()).isEqualTo(FROM);
        assertThat(export.toExclusive()).isEqualTo(TO);
        assertThat(export.dataCutoff()).isEqualTo(newer.fetchedAt());
        assertThat(export.providerSources()).containsExactly("economic-times", "moneycontrol");
        assertThat(export.events()).hasSize(2);
        assertThat(export.events().get(0).availableAt()).isEqualTo(older.fetchedAt());
        assertThat(export.events().get(0).publishedAt()).isEqualTo(older.publishedAt());
        assertThat(export.events().get(0).sourceId()).isEqualTo(older.id());
        assertThat(export.events().get(1).linkedSymbols()).containsExactly("INFY", "TCS");
    }

    @Test
    void rejectsInvalidWindowAndUnboundedLimit() {
        NewsRepository repository = mock(NewsRepository.class);
        NewsResearchExportService service = new NewsResearchExportService(repository);

        assertThatThrownBy(() -> service.export(TO, FROM, 100))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("fromInclusive");
        assertThatThrownBy(() -> service.export(FROM, TO, 20_001))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("20000");
    }

    private static NewsItem item(String id, String source, Instant publishedAt, Instant fetchedAt,
                                 String contentHash, List<String> symbols) {
        return new NewsItem(
            id,
            source,
            source,
            "https://example.com/" + id,
            publishedAt,
            fetchedAt,
            "title",
            "normalized content",
            contentHash,
            symbols,
            "INGESTED",
            null,
            null,
            null,
            null);
    }
}
