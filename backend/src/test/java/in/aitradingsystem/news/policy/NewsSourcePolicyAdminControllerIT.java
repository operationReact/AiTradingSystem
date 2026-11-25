package in.aitradingsystem.news.policy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import in.aitradingsystem.support.AbstractPostgresIT;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors;
import org.springframework.test.web.servlet.MockMvc;

/** PostgreSQL and ADMIN RBAC contract for recording explicit publisher policy decisions. */
class NewsSourcePolicyAdminControllerIT extends AbstractPostgresIT {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired JdbcTemplate jdbc;

    private org.springframework.test.web.servlet.request.RequestPostProcessor role(String name) {
        return SecurityMockMvcRequestPostProcessors.jwt()
            .jwt(j -> j.issuer("https://test.example").subject("news-policy-it"))
            .authorities(new SimpleGrantedAuthority("ROLE_" + name));
    }

    @AfterEach
    void restoreDefaultDeny() {
        jdbc.update("""
            UPDATE news_source_policy
               SET license_state='REVIEW_PENDING', permitted_uses='{}', reviewer_ref=NULL,
                   terms_ref=NULL, effective_from=NULL, effective_to=NULL, content_ttl_days=30
             WHERE source_id='sebi'
            """);
    }

    @Test
    void onlyAdminCanRecordSupportedPublisherPolicyAndEveryChangeIsAudited() throws Exception {
        String path = "/api/admin/market-intelligence/sources/sebi/policy";
        String body = """
            {"licenseState":"APPROVED",
             "permittedUses":["INGEST","DISPLAY","NLP_TRANSFER","RETAIN"],
             "reviewerRef":"admin:publisher-review-42",
             "termsRef":"https://www.sebi.gov.in/terms/news-use",
             "effectiveFrom":"2026-11-25T00:00:00Z",
             "contentTtlDays":14}
            """;

        mvc.perform(put(path).contentType("application/json").content(body))
            .andExpect(status().isUnauthorized());
        mvc.perform(put(path).header("X-API-Key", API_KEY)
                .contentType("application/json").content(body))
            .andExpect(status().isForbidden());
        mvc.perform(put(path).with(role("CUSTOMER"))
                .contentType("application/json").content(body))
            .andExpect(status().isForbidden());

        String raw = mvc.perform(put(path).with(role("ADMIN"))
                .contentType("application/json").content(body))
            .andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString();
        JsonNode response = mapper.readTree(raw);
        assertThat(response.path("licenseState").asText()).isEqualTo("APPROVED");
        assertThat(response.path("permittedUses").size()).isEqualTo(4);
        assertThat(response.path("contentTtlDays").asInt()).isEqualTo(14);
        assertThat(raw).doesNotContain("GEMINI_API_KEY", "apiKey", "secret");

        assertThat(jdbc.queryForObject(
            "SELECT license_state FROM news_source_policy WHERE source_id='sebi'", String.class))
            .isEqualTo("APPROVED");
        assertThat(jdbc.queryForObject("""
            SELECT count(*) FROM audit_event
             WHERE event_type='NEWS_SOURCE_POLICY_UPDATED' AND entity_type='NEWS_SOURCE'
               AND entity_id='sebi' AND actor='news-policy-it'
            """, Integer.class)).isGreaterThan(0);
    }

    @Test
    void approvalRequiresRightsReferencesAndUnsupportedPublishersCannotBeAdded() throws Exception {
        String body = """
            {"licenseState":"APPROVED","permittedUses":["INGEST"],
             "reviewerRef":"admin:review-1","effectiveFrom":"2026-11-25T00:00:00Z",
             "contentTtlDays":30}
            """;
        mvc.perform(put("/api/admin/market-intelligence/sources/sebi/policy").with(role("ADMIN"))
                .contentType("application/json").content(body))
            .andExpect(status().isBadRequest());
        assertThat(jdbc.queryForObject(
            "SELECT license_state FROM news_source_policy WHERE source_id='sebi'", String.class))
            .isEqualTo("REVIEW_PENDING");

        mvc.perform(put("/api/admin/market-intelligence/sources/unknown/policy").with(role("ADMIN"))
                .contentType("application/json").content(body))
            .andExpect(status().isNotFound());
    }

    @Test
    void revocationClearsPermittedUsesAndWinsOverPreviousApproval() throws Exception {
        String approve = """
            {"licenseState":"APPROVED","permittedUses":["INGEST"],
             "reviewerRef":"admin:review-2","termsRef":"owner-attestation:sebi-rights-2",
             "effectiveFrom":"2026-11-25T00:00:00Z","contentTtlDays":30}
            """;
        String revoke = """
            {"licenseState":"REVOKED","permittedUses":[],
             "reviewerRef":"admin:revocation-2","contentTtlDays":30}
            """;
        String path = "/api/admin/market-intelligence/sources/sebi/policy";
        mvc.perform(put(path).with(role("ADMIN")).contentType("application/json").content(approve))
            .andExpect(status().isOk());
        mvc.perform(put(path).with(role("ADMIN")).contentType("application/json").content(revoke))
            .andExpect(status().isOk());
        assertThat(jdbc.queryForObject(
            "SELECT license_state FROM news_source_policy WHERE source_id='sebi'", String.class))
            .isEqualTo("REVOKED");
        assertThat(jdbc.queryForObject("""
            SELECT cardinality(permitted_uses) FROM news_source_policy WHERE source_id='sebi'
            """, Integer.class)).isZero();
    }
}
