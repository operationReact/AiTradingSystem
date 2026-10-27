package in.aitradingsystem.news;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import in.aitradingsystem.support.AbstractPostgresIT;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;

class NewsApiIT extends AbstractPostgresIT {
    private static final String KEY_HEADER = "X-API-Key";

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired JdbcClient jdbc;

    @Test
    void requiresApiKey() throws Exception {
        mvc.perform(get("/api/news/articles")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/news/status")).andExpect(status().isUnauthorized());
    }

    @Test
    void ingestDeduplicatesByNormalizedUrl() throws Exception {
        String slug = UUID.randomUUID().toString();
        String url = "https://news.example.com/markets/" + slug;
        Map<String, Object> body = Map.of("articles", java.util.List.of(
            article(url, "First headline"),
            article("HTTPS://NEWS.EXAMPLE.COM/markets/" + slug + "/", "Same story")));

        mvc.perform(post("/api/news/articles").header(KEY_HEADER, AbstractPostgresIT.API_KEY)
                .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.received").value(2))
            .andExpect(jsonPath("$.inserted").value(1))
            .andExpect(jsonPath("$.duplicates").value(1));

        mvc.perform(post("/api/news/articles").header(KEY_HEADER, AbstractPostgresIT.API_KEY)
                .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.inserted").value(0));
    }

    @Test
    void analysisWithoutConfiguredProviderIsUnavailableAndNotFabricated() throws Exception {
        String url = "https://news.example.com/unconfigured/" + UUID.randomUUID();
        mvc.perform(post("/api/news/articles").header(KEY_HEADER, AbstractPostgresIT.API_KEY)
            .contentType(MediaType.APPLICATION_JSON)
            .content(json.writeValueAsString(Map.of("articles", java.util.List.of(article(url, "Unanalysed"))))))
            .andExpect(status().isOk());

        String id = findArticleId(url);
        mvc.perform(post("/api/news/articles/" + id + "/analyze").header(KEY_HEADER, AbstractPostgresIT.API_KEY))
            .andExpect(status().isServiceUnavailable())
            .andExpect(jsonPath("$.errorCode").value("UNCONFIGURED"));

        mvc.perform(get("/api/news/status").header(KEY_HEADER, AbstractPostgresIT.API_KEY))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.configured").value(false));
    }

    @Test
    void unknownArticleIsNotFound() throws Exception {
        mvc.perform(post("/api/news/articles/" + UUID.randomUUID() + "/analyze").header(KEY_HEADER, AbstractPostgresIT.API_KEY))
            .andExpect(status().isNotFound());
    }

    private static Map<String, Object> article(String url, String title) {
        return Map.of("source", "Test Wire", "title", title, "url", url,
            "excerpt", "Synthetic test article.", "publishedAt", Instant.parse("2025-01-02T09:00:00Z").toString());
    }

    private String findArticleId(String url) {
        return jdbc.sql("SELECT id::text FROM news_articles WHERE url = :url")
            .param("url", NewsService.normalizeUrl(url))
            .query(String.class).single();
    }
}
