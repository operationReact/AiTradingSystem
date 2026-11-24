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
}
