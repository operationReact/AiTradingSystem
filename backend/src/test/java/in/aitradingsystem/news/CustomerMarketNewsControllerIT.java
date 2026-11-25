package in.aitradingsystem.news;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import in.aitradingsystem.news.policy.NewsSourcePolicyTestSupport;
import in.aitradingsystem.support.AbstractPostgresIT;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors;
import org.springframework.test.web.servlet.MockMvc;

/** Source permission and customer RBAC proof with fictional publisher fixtures only. */
class CustomerMarketNewsControllerIT extends AbstractPostgresIT {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired JdbcClient jdbc;
    @Autowired NewsService news;

    private static String unique(String prefix) {
        return prefix + "-" + UUID.randomUUID();
    }

    private void insertFixture(String source, String url) {
        news.ingest(new NewsService.IngestRequest(List.of(
            new NewsService.ArticleInput(source, null, "FIXTURE announcement only",
                url, "Synthetic body for customer contract verification.", Instant.now()))));
    }

    private org.springframework.test.web.servlet.request.RequestPostProcessor customer() {
        return SecurityMockMvcRequestPostProcessors.jwt()
            .jwt(j -> j.issuer("https://test.example").subject("customer-news-it"))
            .authorities(new SimpleGrantedAuthority("ROLE_CUSTOMER"));
    }

    @Test
    void customerEndpointRequiresCustomerRoleNotLegacyOperatorKey() throws Exception {
        mvc.perform(get("/api/customer/market-intelligence/articles"))
            .andExpect(status().isUnauthorized());
        mvc.perform(get("/api/customer/market-intelligence/articles")
            .header("X-API-Key", API_KEY)).andExpect(status().isForbidden());
    }

    @Test
    void onlyDisplayApprovedSourcesReachCustomerAndNoAnalysisMetadataLeaks() throws Exception {
        String allowed = unique("fixture-approved");
        String blocked = unique("fixture-blocked");
        String allowedUrl = "https://news.example.test/allowed/" + UUID.randomUUID();
        String blockedUrl = "https://news.example.test/blocked/" + UUID.randomUUID();
        NewsSourcePolicyTestSupport.approveFixture(jdbc, allowed);
        insertFixture(allowed, allowedUrl);
        insertFixture(blocked, blockedUrl);

        String body = mvc.perform(get("/api/customer/market-intelligence/articles")
                .with(customer()).param("page", "0").param("size", "50"))
            .andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString();

        JsonNode response = mapper.readTree(body);
        assertThat(response.path("totalItems").isNumber()).isTrue();
        assertThat(body).contains(allowedUrl);
        assertThat(body).doesNotContain(blockedUrl);
        assertThat(response.toString()).doesNotContain("providerRequestId", "promptHash", "responseFingerprint");
    }

    @Test
    void rightsRevocationHidesPreviouslyPermittedPublisherContent() throws Exception {
        String source = unique("fixture-revoked");
        String url = "https://news.example.test/revoked/" + UUID.randomUUID();
        NewsSourcePolicyTestSupport.approveFixture(jdbc, source);
        insertFixture(source, url);
        jdbc.sql("UPDATE news_source_policy SET license_state = 'REVOKED' WHERE source_id = :source")
            .param("source", source).update();

        String body = mvc.perform(get("/api/customer/market-intelligence/articles")
                .with(customer()).param("size", "50"))
            .andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString();
        assertThat(body).doesNotContain(url);
    }

    @Test
    void invalidPageSizeIsRejectedByRequestValidation() throws Exception {
        mvc.perform(get("/api/customer/market-intelligence/articles")
                .with(customer()).param("size", "51"))
            .andExpect(status().isBadRequest());
    }
    @Test
    void stockFilterReturnsOnlyLatestSuccessfulAnalysisForExactSymbol() throws Exception {
        String source = unique("fixture-stock-impact");
        String tcsUrl = "https://news.example.test/stock/" + UUID.randomUUID();
        String otherUrl = "https://news.example.test/stock/" + UUID.randomUUID();
        NewsSourcePolicyTestSupport.approveFixture(jdbc, source);
        insertFixture(source, tcsUrl);
        insertFixture(source, otherUrl);
        UUID tcsId = UUID.fromString(jdbc.queryForObject(
            "SELECT id::text FROM news_articles WHERE url = ?", String.class, tcsUrl));
        UUID otherId = UUID.fromString(jdbc.queryForObject(
            "SELECT id::text FROM news_articles WHERE url = ?", String.class, otherUrl));
        insertAnalysis(tcsId, "[\"TCS\"]");
        insertAnalysis(otherId, "[\"RELIANCE\"]");

        String body = mvc.perform(get("/api/customer/market-intelligence/articles")
                .with(customer()).param("symbol", "tcs").param("size", "50"))
            .andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString();

        JsonNode response = mapper.readTree(body);
        assertThat(response.path("totalItems").asInt()).isEqualTo(1);
        assertThat(body).contains(tcsUrl, "\"analysisStatus\":\"ANALYZED\"",
            "\"sentiment\":\"NEUTRAL\"", "\"impactedSymbols\":[\"TCS\"]");
        assertThat(body).doesNotContain(otherUrl, "providerRequestId", "promptHash",
            "requestFingerprint", "responseFingerprint");
    }

    @Test
    void invalidStockFilterIsRejected() throws Exception {
        mvc.perform(get("/api/customer/market-intelligence/articles")
                .with(customer()).param("symbol", "TCS,RELIANCE"))
            .andExpect(status().isBadRequest());
    }

    private void insertAnalysis(UUID articleId, String symbolsJson) {
        jdbc.update("""
            INSERT INTO news_analyses (
                id, article_id, provider, model, status, sentiment, category, confidence,
                impacted_symbols, evidence, risks, latency_ms, created_at)
            VALUES (?, ?, 'GEMINI', 'fixture-model', 'ANALYZED', 'NEUTRAL', 'OTHER',
                    0.750, ?, 'Synthetic evidence only', '[]', 10, CURRENT_TIMESTAMP)
            """, UUID.randomUUID(), articleId, symbolsJson);
    }

}
