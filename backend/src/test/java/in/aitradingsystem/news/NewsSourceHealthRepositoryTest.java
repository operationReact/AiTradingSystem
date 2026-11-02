package in.aitradingsystem.news;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;

class NewsSourceHealthRepositoryTest {
    @Test
    void truncatesProviderErrorDetailBeforePersistence() {
        JdbcTemplate jdbc = org.mockito.Mockito.mock(JdbcTemplate.class);
        NewsSourceHealthRepository repository = new NewsSourceHealthRepository(jdbc);
        String detail = "x".repeat(450);
        Instant start = Instant.parse("2026-11-02T05:00:00Z");
        Instant finish = Instant.parse("2026-11-02T05:00:02Z");

        repository.record("sebi", start, finish, "FAILED", 0, 0, 0, "IOException", detail);

        ArgumentCaptor<Object[]> args = ArgumentCaptor.forClass(Object[].class);
        org.mockito.Mockito.verify(jdbc).update(org.mockito.ArgumentMatchers.anyString(), args.capture());
        Object[] values = args.getValue();
        assertThat(values[0]).isEqualTo("sebi");
        assertThat(values[3]).isEqualTo("FAILED");
        assertThat(values[7]).isEqualTo("IOException");
        assertThat((String) values[8]).hasSize(300);
    }
}
