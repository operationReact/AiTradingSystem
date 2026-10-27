package in.aitradingsystem.news;

import static org.junit.jupiter.api.Assertions.assertTrue;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class NewsMigrationTest {
    @Test
    void migrationStoresProvenanceDeduplicationAndSummaryMetadata() throws IOException {
        try (var stream = getClass().getResourceAsStream("/db/migration/V12__provenance_aware_news.sql")) {
            org.junit.jupiter.api.Assertions.assertNotNull(stream, "Forward-only V12 migration must be packaged");
            String sql = new String(stream.readAllBytes(), StandardCharsets.UTF_8).toLowerCase();
            for (String column : new String[] {"source_url", "publisher", "published_at", "fetched_at",
                "title", "normalized_content", "content_hash", "linked_symbols", "ingestion_status",
                "provider", "model", "generated_at"}) {
                assertTrue(sql.contains(column), "Migration must persist " + column);
            }
            assertTrue(sql.contains("unique"));
            assertTrue(sql.contains("on delete cascade"));
        }
    }
}
