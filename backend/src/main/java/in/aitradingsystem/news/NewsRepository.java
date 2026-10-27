package in.aitradingsystem.news;

import in.aitradingsystem.domain.NewsItem;
import java.sql.PreparedStatement;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class NewsRepository {
    private final JdbcTemplate jdbc;

    public NewsRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<String> insert(NewsArticle article) {
        List<String> ids = jdbc.query(connection -> {
            PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO news_item
                    (source, publisher, source_url, published_at, title, normalized_content,
                     content_hash, linked_symbols)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (content_hash) DO NOTHING
                RETURNING id::text
                """);
            statement.setString(1, article.source());
            statement.setString(2, article.publisher());
            statement.setString(3, article.sourceUrl());
            if (article.publishedAt() == null) statement.setNull(4, java.sql.Types.TIMESTAMP_WITH_TIMEZONE);
            else statement.setTimestamp(4, Timestamp.from(article.publishedAt()));
            statement.setString(5, article.title());
            statement.setString(6, article.normalizedContent());
            statement.setString(7, article.contentHash());
            statement.setArray(8, connection.createArrayOf("text", article.linkedSymbols().toArray()));
            return statement;
        }, (rs, row) -> rs.getString(1));
        return ids.stream().findFirst();
    }

    public void saveSummary(String id, String provider, String model, String summary, Timestamp generatedAt) {
        jdbc.update("""
            INSERT INTO news_summary (news_id, provider, model, summary, generated_at)
            VALUES (?::uuid, ?, ?, ?, ?)
            ON CONFLICT (news_id) DO UPDATE SET provider = EXCLUDED.provider,
                model = EXCLUDED.model, summary = EXCLUDED.summary, generated_at = EXCLUDED.generated_at
            """, id, provider, model, summary, generatedAt);
        jdbc.update("UPDATE news_item SET ingestion_status = 'SUMMARIZED' WHERE id = ?::uuid", id);
    }

    public List<NewsItem> recent(int limit, int offset) {
        return jdbc.query("""
            SELECT n.id::text, n.source, n.publisher, n.source_url, n.published_at, n.fetched_at,
                   n.title, n.normalized_content, n.content_hash, n.linked_symbols,
                   n.ingestion_status, s.summary, s.provider AS summary_provider,
                   s.model AS summary_model, s.generated_at AS summary_generated_at
            FROM news_item n
            LEFT JOIN news_summary s ON s.news_id = n.id
            ORDER BY n.published_at DESC NULLS LAST, n.fetched_at DESC, n.id
            LIMIT ? OFFSET ?
            """, (rs, row) -> {
            java.sql.Array sqlSymbols = rs.getArray("linked_symbols");
            List<String> symbols = new ArrayList<>();
            if (sqlSymbols != null) {
                Object[] values = (Object[]) sqlSymbols.getArray();
                for (Object value : values) symbols.add(String.valueOf(value));
                sqlSymbols.free();
            }
            Timestamp published = rs.getTimestamp("published_at");
            Timestamp fetched = rs.getTimestamp("fetched_at");
            Timestamp summaryGenerated = rs.getTimestamp("summary_generated_at");
            return new NewsItem(rs.getString("id"), rs.getString("source"), rs.getString("publisher"),
                rs.getString("source_url"), published == null ? null : published.toInstant(),
                fetched.toInstant(), rs.getString("title"), rs.getString("normalized_content"),
                rs.getString("content_hash"), List.copyOf(symbols), rs.getString("ingestion_status"),
                rs.getString("summary"), rs.getString("summary_provider"), rs.getString("summary_model"),
                summaryGenerated == null ? null : summaryGenerated.toInstant());
        }, limit, offset);
    }

    public void deleteExpired(int retentionDays) {
        jdbc.update("DELETE FROM news_item WHERE fetched_at < CURRENT_TIMESTAMP - (? * INTERVAL '1 day')",
            retentionDays);
    }
}
