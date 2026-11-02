package in.aitradingsystem.news;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class NewsSourceHealthRepository {
    private final JdbcTemplate jdbc;

    public NewsSourceHealthRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void record(
        String source,
        Instant startedAt,
        Instant finishedAt,
        String status,
        int fetchedItems,
        int insertedItems,
        int summarizedItems,
        String errorCode,
        String errorDetail) {
        jdbc.update("""
            INSERT INTO news_source_run (
                source, started_at, finished_at, status, fetched_items, inserted_items,
                summarized_items, error_code, error_detail)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
            """,
            source,
            Timestamp.from(startedAt),
            Timestamp.from(finishedAt),
            status,
            fetchedItems,
            insertedItems,
            summarizedItems,
            errorCode,
            truncate(errorDetail, 300));
    }

    public List<SourceHealth> latest() {
        return jdbc.query("""
            SELECT DISTINCT ON (source)
                   source, status, started_at, finished_at, fetched_items, inserted_items,
                   summarized_items, error_code, error_detail
            FROM news_source_run
            ORDER BY source, finished_at DESC
            """, (rs, rowNum) -> new SourceHealth(
                rs.getString("source"),
                rs.getString("status"),
                rs.getTimestamp("started_at").toInstant(),
                rs.getTimestamp("finished_at").toInstant(),
                rs.getInt("fetched_items"),
                rs.getInt("inserted_items"),
                rs.getInt("summarized_items"),
                rs.getString("error_code"),
                rs.getString("error_detail")));
    }

    public record SourceHealth(
        String source,
        String status,
        Instant startedAt,
        Instant finishedAt,
        int fetchedItems,
        int insertedItems,
        int summarizedItems,
        String errorCode,
        String errorDetail) {}

    private static String truncate(String value, int maxLength) {
        if (value == null) return null;
        return value.length() <= maxLength ? value : value.substring(0, maxLength);
    }
}
