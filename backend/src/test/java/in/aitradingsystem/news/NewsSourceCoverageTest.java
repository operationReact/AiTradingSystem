package in.aitradingsystem.news;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class NewsSourceCoverageTest {
    @Test
    void includesVerifiedSebiRegulatoryRssSource() {
        NewsSource sebi = NewsSource.byId("sebi");

        assertThat(sebi.publisher()).isEqualTo("Securities and Exchange Board of India");
        assertThat(sebi.feedUri().toString()).isEqualTo("https://www.sebi.gov.in/sebirss.xml");
        assertThat(sebi.articleHosts()).contains("www.sebi.gov.in", "sebi.gov.in");
    }

    @Test
    void healthEndpointIncludesNeverRunAndLatestRunSources() {
        NewsSourceHealthRepository repository = mock(NewsSourceHealthRepository.class);
        Instant finished = Instant.now().minusSeconds(30);
        when(repository.latest()).thenReturn(List.of(
            new NewsSourceHealthRepository.SourceHealth(
                "sebi",
                "SUCCESS",
                finished.minusSeconds(2),
                finished,
                8,
                3,
                0,
                null,
                null)));

        List<NewsSourceHealthController.SourceHealthView> health =
            new NewsSourceHealthController(repository).health();

        assertThat(health).extracting(NewsSourceHealthController.SourceHealthView::source)
            .contains("moneycontrol", "economic-times", "sebi");
        var sebi = health.stream().filter(row -> row.source().equals("sebi")).findFirst().orElseThrow();
        assertThat(sebi.status()).isEqualTo("SUCCESS");
        assertThat(sebi.fetchedItems()).isEqualTo(8);
        assertThat(sebi.insertedItems()).isEqualTo(3);
        assertThat(sebi.ageSeconds()).isNotNegative();
        var moneycontrol = health.stream()
            .filter(row -> row.source().equals("moneycontrol"))
            .findFirst()
            .orElseThrow();
        assertThat(moneycontrol.status()).isEqualTo("NEVER_RUN");
        assertThat(moneycontrol.ageSeconds()).isNull();
    }
}
