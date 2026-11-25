package in.aitradingsystem.news;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import in.aitradingsystem.support.AbstractPostgresIT;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors;
import org.springframework.test.web.servlet.MockMvc;

/** Real Spring RBAC and PostgreSQL health contracts; no external model or AWS calls. */
class NewsIntelligenceAdminHealthControllerIT extends AbstractPostgresIT {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;

    private org.springframework.test.web.servlet.request.RequestPostProcessor role(String name) {
        return SecurityMockMvcRequestPostProcessors.jwt()
            .jwt(j -> j.issuer("https://test.example").subject("news-health-it"))
            .authorities(new SimpleGrantedAuthority("ROLE_" + name));
    }

    @Test
    void requiresAdminNotOperatorCustomerOrUnauthenticated() throws Exception {
        String path = "/api/admin/market-intelligence/health";
        mvc.perform(get(path)).andExpect(status().isUnauthorized());
        mvc.perform(get(path).header("X-API-Key", API_KEY))
            .andExpect(status().isForbidden());
        mvc.perform(get(path).with(role("CUSTOMER")))
            .andExpect(status().isForbidden());
        mvc.perform(get(path).with(role("ADMIN")))
            .andExpect(status().isOk());
    }

    @Test
    void exposesGenuineCountersAndSafeSourceStatusWithoutCredentials() throws Exception {
        String raw = mvc.perform(get("/api/admin/market-intelligence/health")
                .with(role("ADMIN")))
            .andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString();

        JsonNode body = mapper.readTree(raw);
        assertThat(body.path("sources").size()).isEqualTo(NewsSource.SUPPORTED.size());
        assertThat(body.path("receiptCount").asLong()).isGreaterThanOrEqualTo(0L);
        assertThat(body.path("eventCount").asLong()).isGreaterThanOrEqualTo(0L);
        assertThat(body.path("model").path("selectedProvider").isTextual()).isTrue();
        assertThat(body.path("model").path("realProviderCallVerified").asBoolean()).isFalse();
        assertThat(body.path("model").path("selectedProviderConfigured").isBoolean()).isTrue();
        assertThat(body.path("model").path("recordedGeminiAnalysisReceipts").asLong()).isGreaterThanOrEqualTo(0L);
        assertThat(body.path("model").path("geminiReadiness").isTextual()).isTrue();
        assertThat(body.path("model").path("lastGeminiAttemptOutcome").asText()).isEqualTo("NO_OBSERVATION");
        // The application omits null JSON properties; either omitted or explicit null is unknown.
        assertThat(body.path("model").hasNonNull("lastGeminiAttemptAt")).isFalse();
        assertThat(body.path("eventLedgerEnabled").asBoolean()).isFalse();
        assertThat(body.path("analysisSchedulerEnabled").asBoolean()).isFalse();
        assertThat(raw).doesNotContain("apiKey", "geminiApiKey",
            "clientSecret", "totpSecret", "errorDetail", "ssmParameterValue");
        assertThat(body.path("sources").toString()).contains(
            "nse-announcements", "moneycontrol", "economic-times", "sebi");
    }
}
