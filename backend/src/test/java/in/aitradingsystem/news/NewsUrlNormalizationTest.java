package in.aitradingsystem.news;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class NewsUrlNormalizationTest {
    @Test
    void normalizesSchemeHostAndTrailingSlashAndDropsFragment() {
        assertThat(NewsService.normalizeUrl("HTTPS://Example.COM/news/a/?x=1#top"))
            .isEqualTo("https://example.com/news/a?x=1");
    }

    @Test
    void equivalentUrlsMapToSameKey() {
        assertThat(NewsService.normalizeUrl("https://example.com/a/"))
            .isEqualTo(NewsService.normalizeUrl("https://EXAMPLE.com/a"));
    }

    @Test
    void rejectsNonHttpSchemes() {
        assertThatThrownBy(() -> NewsService.normalizeUrl("javascript:alert(1)"))
            .isInstanceOf(IllegalArgumentException.class);
    }
}
