package in.aitradingsystem.news;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import in.aitradingsystem.news.policy.NewsSourcePolicyTestSupport;
import in.aitradingsystem.support.AbstractPostgresIT;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors;
import org.springframework.test.web.servlet.MockMvc;

/** PostgreSQL V128/V129 API contract. All publisher events below are fictional. */
class CustomerNewsEventsControllerIT extends AbstractPostgresIT {
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired JdbcClient client;
    @Autowired ObjectMapper mapper;

    private static String uniqueSource() {
        return "fictional-" + UUID.randomUUID();
    }

    private org.springframework.test.web.servlet.request.RequestPostProcessor customer() {
        return SecurityMockMvcRequestPostProcessors.jwt()
            .jwt(j -> j.issuer("https://test.example").subject("events-customer"))
            .authorities(new SimpleGrantedAuthority("ROLE_CUSTOMER"));
    }

    private UUID event(String source, Instant observedAt, Instant analysisKnownAt) {
        NewsSourcePolicyTestSupport.approveFixture(client, source);
        UUID receiptId = UUID.randomUUID();
        UUID eventId = UUID.randomUUID();
        String hash = UUID.randomUUID().toString().replace("-", "") +
            UUID.randomUUID().toString().replace("-", "");
        String url = "https://news.example.test/fictional-event/" + eventId;
        jdbc.update("""
                INSERT INTO news_ingest_receipt
                    (receipt_id, source_id, source_url, title, content_hash,
                     published_at, first_observed_at, fetched_at)
                VALUES (?, ?, ?, 'Fictional corporate disclosure', ?, ?, ?, ?)
                """, receiptId, source, url, hash,
            Timestamp.from(observedAt.minusSeconds(3600)),
            Timestamp.from(observedAt), Timestamp.from(observedAt));
        jdbc.update("""
                INSERT INTO news_canonical_event
                    (event_id, event_class, status, first_seen_at, current_version)
                VALUES (?, 'UNCLASSIFIED', 'ACTIVE', ?, 1)
                """, eventId, Timestamp.from(observedAt));
        jdbc.update("""
                INSERT INTO news_event_version(event_id, version, effective_known_at, category)
                VALUES (?, 1, ?, 'UNCLASSIFIED')
                """, eventId, Timestamp.from(analysisKnownAt));
        jdbc.update("""
                INSERT INTO news_event_evidence
                    (event_id, version, receipt_id, evidence_kind, observed_at)
                VALUES (?, 1, ?, 'OTHER', ?)
                """, eventId, receiptId, Timestamp.from(observedAt));
        return eventId;
    }

    private JsonNode response(String source) throws Exception {
        String raw = mvc.perform(get("/api/customer/market-intelligence/events")
                .with(customer()).param("sourceId", source).param("page", "0").param("size", "20"))
            .andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString();
        return mapper.readTree(raw);
    }

    @Test
    void customerSeesOnlySourceBackedOriginalHeadlineAndUnknownIssuerState() throws Exception {
        String source = uniqueSource();
        Instant observed = Instant.now().minusSeconds(90).truncatedTo(ChronoUnit.MICROS);
        UUID id = event(source, observed, observed);
        JsonNode page = response(source);

        assertThat(page.path("totalItems").asInt()).isEqualTo(1);
        JsonNode item = page.path("items").get(0);
        assertThat(item.path("eventId").asText()).isEqualTo(id.toString());
        assertThat(item.path("headline").asText()).isEqualTo("Fictional corporate disclosure");
        assertThat(item.path("eventClass").asText()).isEqualTo("UNCLASSIFIED");
        assertThat(item.path("instrumentResolutionStatus").asText()).isEqualTo("UNVERIFIED");
        assertThat(item.path("sourceId").asText()).isEqualTo(source);
        assertThat(Instant.parse(item.path("firstObservedAt").asText())).isEqualTo(observed);
        assertThat(Instant.parse(item.path("publishedAt").asText()))
            .isEqualTo(observed.minusSeconds(3600));
        assertThat(page.toString()).doesNotContain("GEMINI", "BUY", "SELL", "apiKey");
    }

    @Test
    void revokedDisplayPolicyHidesAlreadyStoredImmutableEvent() throws Exception {
        String source = uniqueSource();
        Instant observed = Instant.now().minusSeconds(90).truncatedTo(ChronoUnit.MICROS);
        event(source, observed, observed);
        assertThat(response(source).path("totalItems").asInt()).isEqualTo(1);
        client.sql("UPDATE news_source_policy SET license_state='REVOKED' WHERE source_id=:source")
            .param("source", source).update();
        assertThat(response(source).path("totalItems").asInt()).isZero();
    }

    @Test
    void futureAnalysisAvailabilityNeverAppearsInCurrentTimeline() throws Exception {
        String source = uniqueSource();
        Instant observed = Instant.now().minusSeconds(90).truncatedTo(ChronoUnit.MICROS);
        event(source, observed, Instant.now().plusSeconds(3600));
        assertThat(response(source).path("items")).isEmpty();
    }

    @Test
    void requiresCustomerRoleAndBoundsPagination() throws Exception {
        mvc.perform(get("/api/customer/market-intelligence/events"))
            .andExpect(status().isUnauthorized());
        mvc.perform(get("/api/customer/market-intelligence/events")
                .header("X-API-Key", API_KEY))
            .andExpect(status().isForbidden());
        mvc.perform(get("/api/customer/market-intelligence/events")
                .with(customer()).param("size", "51"))
            .andExpect(status().isBadRequest());
    }
}
