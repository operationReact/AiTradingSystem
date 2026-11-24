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

/** Source-rights and immutable version-history integration with fictional publishers. */
class CustomerNewsEventDetailControllerIT extends AbstractPostgresIT {
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired JdbcClient client;
    @Autowired ObjectMapper mapper;

    private static final String PREFIX = "/api/customer/market-intelligence/events/";
    private static final Instant OBSERVED = Instant.parse("2026-11-24T04:00:00Z");

    private org.springframework.test.web.servlet.request.RequestPostProcessor customer() {
        return SecurityMockMvcRequestPostProcessors.jwt()
            .jwt(jwt -> jwt.issuer("https://test.example").subject("news-event-detail-it"))
            .authorities(new SimpleGrantedAuthority("ROLE_CUSTOMER"));
    }

    private record Fixture(UUID eventId, String firstSource, String secondSource) {}

    private Fixture fixture(boolean withCorrection, boolean correctionKnownInFuture) {
        UUID eventId = UUID.randomUUID();
        String a = "fixture-publisher-a-" + UUID.randomUUID();
        String b = "fixture-publisher-b-" + UUID.randomUUID();
        NewsSourcePolicyTestSupport.approveFixture(client, a);
        NewsSourcePolicyTestSupport.approveFixture(client, b);

        jdbc.update("""
                INSERT INTO news_canonical_event
                    (event_id, event_class, status, first_seen_at, current_version)
                VALUES (?, 'UNCLASSIFIED', 'ACTIVE', ?, 1)
                """, eventId, Timestamp.from(OBSERVED));

        UUID firstReceipt = receipt(a, "Initial fictional filing", OBSERVED);
        version(eventId, 1, OBSERVED, null, false);
        evidence(eventId, 1, firstReceipt, OBSERVED);

        if (withCorrection) {
            Instant secondKnown = correctionKnownInFuture
                ? Instant.now().plusSeconds(3600)
                : OBSERVED.plusSeconds(240);
            UUID secondReceipt = receipt(b, "Fictional corrected filing", OBSERVED.plusSeconds(180));
            version(eventId, 2, secondKnown, 1, false);
            evidence(eventId, 2, secondReceipt, OBSERVED.plusSeconds(180));
            jdbc.update("UPDATE news_canonical_event SET current_version = 2 WHERE event_id = ?",
                eventId);
        }
        return new Fixture(eventId, a, b);
    }

    private UUID receipt(String source, String title, Instant observed) {
        UUID id = UUID.randomUUID();
        String hash = UUID.randomUUID().toString().replace("-", "")
            + UUID.randomUUID().toString().replace("-", "");
        jdbc.update("""
                INSERT INTO news_ingest_receipt
                    (receipt_id, source_id, source_url, title, content_hash,
                     published_at, first_observed_at, fetched_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """, id, source, "https://news.example.test/fictional/" + id, title,
            hash, Timestamp.from(observed.minusSeconds(600)), Timestamp.from(observed),
            Timestamp.from(observed));
        return id;
    }

    private void version(UUID eventId, int number, Instant knownAt,
                         Integer correctionOfVersion, boolean retracted) {
        jdbc.update("""
                INSERT INTO news_event_version
                    (event_id, version, effective_known_at, category,
                     correction_of_version, retraction)
                VALUES (?, ?, ?, 'UNCLASSIFIED', ?, ?)
                """, eventId, number, Timestamp.from(knownAt), correctionOfVersion, retracted);
    }

    private void evidence(UUID eventId, int version, UUID receiptId, Instant observed) {
        jdbc.update("""
                INSERT INTO news_event_evidence
                    (event_id, version, receipt_id, evidence_kind, observed_at)
                VALUES (?, ?, ?, 'OTHER', ?)
                """, eventId, version, receiptId, Timestamp.from(observed));
    }

    private JsonNode get(UUID eventId) throws Exception {
        String json = mvc.perform(get(PREFIX + eventId).with(customer()))
            .andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString();
        return mapper.readTree(json);
    }

    @Test
    void customerGetsVersionedCorrectionsAndTwoOriginalPublisherEvidenceLinks() throws Exception {
        Fixture f = fixture(true, false);
        JsonNode body = get(f.eventId());

        assertThat(body.path("eventId").asText()).isEqualTo(f.eventId().toString());
        assertThat(body.path("currentVersion").asInt()).isEqualTo(2);
        assertThat(body.path("versions").size()).isEqualTo(2);
        assertThat(body.path("versions").get(0).path("version").asInt()).isEqualTo(2);
        assertThat(body.path("versions").get(0).path("correctionOfVersion").asInt()).isEqualTo(1);
        assertThat(body.path("versions").get(0).path("evidence").get(0).path("sourceId").asText())
            .isEqualTo(f.secondSource());
        assertThat(body.path("versions").get(1).path("evidence").get(0).path("sourceId").asText())
            .isEqualTo(f.firstSource());
        assertThat(body.path("versions").get(0).path("evidence").get(0).path("title").asText())
            .isEqualTo("Fictional corrected filing");
        assertThat(body.path("instrumentResolutionStatus").asText()).isEqualTo("UNVERIFIED");
        assertThat(body.path("versionsTruncated").asBoolean()).isFalse();
        assertThat(body.toString()).doesNotContain("providerRequestId", "apiKey", "BUY", "SELL");
    }

    @Test
    void revokingCurrentCorrectionEvidenceMakesWholeEventUnavailable() throws Exception {
        Fixture f = fixture(true, false);
        assertThat(get(f.eventId()).path("versions").size()).isEqualTo(2);
        client.sql("UPDATE news_source_policy SET license_state='REVOKED' WHERE source_id=:source")
            .param("source", f.secondSource()).update();
        mvc.perform(get(PREFIX + f.eventId()).with(customer()))
            .andExpect(status().isNotFound());
    }

    @Test
    void futureKnownCurrentRevisionCannotLeakOlderEventVersions() throws Exception {
        Fixture f = fixture(true, true);
        mvc.perform(get(PREFIX + f.eventId()).with(customer()))
            .andExpect(status().isNotFound());
    }

    @Test
    void absenceAndWrongRoleAreSafe() throws Exception {
        Fixture f = fixture(false, false);
        mvc.perform(get(PREFIX + f.eventId())).andExpect(status().isUnauthorized());
        mvc.perform(get(PREFIX + f.eventId()).header("X-API-Key", API_KEY))
            .andExpect(status().isForbidden());
        mvc.perform(get(PREFIX + UUID.randomUUID()).with(customer()))
            .andExpect(status().isNotFound());
        assertThat(get(f.eventId()).path("versions").size()).isEqualTo(1);
    }
}
