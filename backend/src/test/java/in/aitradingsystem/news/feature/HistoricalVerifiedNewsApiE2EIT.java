package in.aitradingsystem.news.feature;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import in.aitradingsystem.config.AngelOneProperties;
import in.aitradingsystem.news.policy.NewsSourcePolicyTestSupport;
import in.aitradingsystem.support.AbstractPostgresIT;
import java.sql.Date;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

/** Real Spring MVC, service and PostgreSQL chain; synthetic data, not publisher certification. */
@Transactional
class HistoricalVerifiedNewsApiE2EIT extends AbstractPostgresIT {
    private static final String PATH = "/api/customer/mind/v1/verified-news-evidence";
    private static final String DIGEST = "a".repeat(64);
    @Autowired JdbcTemplate jdbc;
    @Autowired JdbcClient client;
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired AngelOneProperties angel;

    @Test
    void originalProofOnlyAppearsAfterMappingAndCorrectionsRespectCutoff() throws Exception {
        Fixture f = fixture("AFFECTED_ISSUER");
        assertNoEvidence(read(f.instrument(), f.mapped().minusSeconds(1)));
        JsonNode original = read(f.instrument(), f.cutoff());
        assertThat(original.path("catalogueVersion").asText()).isEqualTo(f.catalogue().toString());
        assertThat(original.path("verifiedListingId").asText()).isEqualTo(f.listing().toString());
        assertThat(original.path("verificationStatus").asText()).isEqualTo("VERIFIED_LISTING_EVENT_EVIDENCE");
        assertThat(original.path("evidenceCount").asInt()).isEqualTo(1);
        JsonNode proof = original.path("evidence").get(0);
        assertThat(proof.path("receiptId").asText()).isEqualTo(f.receipt().toString());
        assertThat(proof.path("eventId").asText()).isEqualTo(f.event().toString());
        assertThat(proof.path("originalUrl").asText()).isEqualTo("https://news.example.test/" + f.receipt());
        assertThat(proof.path("eventVersion").asInt()).isEqualTo(1);
        assertThat(proof.path("retracted").asBoolean()).isFalse();

        Instant correction = f.cutoff().plusSeconds(10);
        jdbc.update("""
            INSERT INTO news_event_version(event_id,version,effective_known_at,category,correction_of_version,retraction)
            VALUES (?,2,?,'CORRECTION',1,true)
            """, f.event(), Timestamp.from(correction));
        addProof(f, 2, correction, "AFFECTED_ISSUER");
        assertThat(read(f.instrument(), f.cutoff()).path("evidence").get(0).path("eventVersion").asInt()).isEqualTo(1);
        JsonNode revisedPage = read(f.instrument(), correction.plusSeconds(1));
        assertThat(revisedPage.path("catalogueVersion").asText()).isEqualTo(f.catalogue().toString());
        assertThat(revisedPage.path("evidenceCount").asInt()).isEqualTo(1);
        JsonNode revised = revisedPage.path("evidence").get(0);
        assertThat(revised.path("eventVersion").asInt()).isEqualTo(2);
        assertThat(revised.path("retracted").asBoolean()).isTrue();
    }

    @Test
    void revokedAndRetrospectivelyReapprovedPublisherNeverLeaksProof() throws Exception {
        Fixture f = fixture("AFFECTED_ISSUER");
        assertThat(read(f.instrument(), f.cutoff()).path("evidenceCount").asInt()).isEqualTo(1);
        jdbc.update("UPDATE news_source_policy SET license_state='REVOKED', updated_at=now() WHERE source_id=?", f.source());
        assertNoEvidence(read(f.instrument(), f.cutoff()));
        jdbc.update("UPDATE news_source_policy SET license_state='APPROVED', updated_at=now() WHERE source_id=?", f.source());
        assertNoEvidence(read(f.instrument(), f.cutoff()));
    }

    @Test
    void mentionsAndUnverifiedTokenNeverBecomeIssuerProof() throws Exception {
        Fixture f = fixture("MENTION_ONLY");
        assertNoEvidence(read(f.instrument(), f.cutoff()));
        jdbc.update("UPDATE instrument_cash_catalogue_history SET trading_symbol='UNATTESTED-EQ' WHERE catalogue_version=?", f.catalogue());
        JsonNode unresolved = read(f.instrument(), f.cutoff());
        assertThat(unresolved.path("verificationStatus").asText()).isEqualTo("LISTING_IDENTITY_UNVERIFIED");
        assertThat(unresolved.path("verifiedListingId").isNull()).isTrue();
        assertNoEvidence(unresolved);
        mvc.perform(get(PATH).param("instrumentId", Long.toString(f.instrument())).param("asOf", f.cutoff().toString()))
            .andExpect(status().isUnauthorized());
    }

    private JsonNode read(long instrument, Instant cutoff) throws Exception {
        String body = mvc.perform(get(PATH)
            .with(jwt().jwt(j -> j.issuer("https://test.example").subject("historical-news-e2e"))
                .authorities(new SimpleGrantedAuthority("ROLE_CUSTOMER")))
            .param("instrumentId", Long.toString(instrument)).param("asOf", cutoff.toString()))
            .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        JsonNode doc = mapper.readTree(body);
        assertThat(doc.path("contractVersion").asText()).isEqualTo("mind.verified-news-evidence.v1");
        assertThat(doc.path("instrumentId").asLong()).isEqualTo(instrument);
        assertThat(Instant.parse(doc.path("researchCutoff").asText())).isEqualTo(cutoff);
        assertThat(doc.path("includedInDecision").isBoolean()).isTrue();
        assertThat(doc.path("includedInDecision").asBoolean()).isFalse();
        assertThat(doc.path("canAuthorizeCapital").isBoolean()).isTrue();
        assertThat(doc.path("canAuthorizeCapital").asBoolean()).isFalse();
        return doc;
    }

    private void assertNoEvidence(JsonNode doc) {
        assertThat(doc.path("evidenceCount").asInt()).isZero();
        assertThat(doc.path("evidence").isArray()).isTrue();
        assertThat(doc.path("evidence").isEmpty()).isTrue();
    }

    private record Fixture(long instrument, UUID catalogue, UUID listing, UUID receipt,
                           UUID event, String source, Instant mapped, Instant cutoff) {}

    private Fixture fixture(String relationship) {
        Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);
        Instant known = now.minusSeconds(500);
        Instant observed = now.minusSeconds(300);
        UUID catalogue = UUID.randomUUID(), security = UUID.randomUUID(), listing = UUID.randomUUID();
        UUID receipt = UUID.randomUUID(), event = UUID.randomUUID();
        long instrument = 1_000_000_000L + java.util.concurrent.ThreadLocalRandom.current().nextLong(1_000_000_000L);
        String token = UUID.randomUUID().toString().replace("-", "");
        String source = "fixture-e2e-" + UUID.randomUUID();
        String symbol = "SYNTHETIC-EQ";
        // Shared integration contexts may leave successful imports with timestamps
        // inside this fixture's historical window. Isolate only overlapping rows
        // in this test transaction; the rollback restores their original times.
        jdbc.update("""
            UPDATE instrument_import_run SET completed_at=?
             WHERE status='SUCCEEDED' AND completed_at >= ? AND completed_at <= ?
            """, Timestamp.from(now), Timestamp.from(now.minusSeconds(200)),
            Timestamp.from(now.minusSeconds(40)));
        jdbc.update("""
            INSERT INTO instrument_import_run(id,status,source_url,completed_at,accepted_count,rejected_count)
            VALUES (?,'SUCCEEDED',?,?,1,0)
            """, catalogue, angel.instrumentMasterUrl(), Timestamp.from(now.minusSeconds(200)));
        jdbc.update("""
            INSERT INTO instrument_cash_catalogue_history(catalogue_version,instrument_id,exchange_segment,
                exchange,symbol_token,trading_symbol,instrument_name,instrument_type,lot_size,tick_size,source_updated_at)
            VALUES (?,?,'NSE_CM','NSE',?,?,'Synthetic issuer','EQ',1,0.05,?)
            """, catalogue, instrument, token, symbol, Timestamp.from(now.minusSeconds(200)));
        NewsSourcePolicyTestSupport.approveFixture(client, source);
        jdbc.update("UPDATE news_source_policy SET updated_at=? WHERE source_id=?", Timestamp.from(now.minusSeconds(600)), source);
        jdbc.update("""
            INSERT INTO golden_security_identity(security_id,issuer_name,identity_source,identity_evidence_digest,created_at)
            VALUES (?,'Synthetic E2E issuer','SYNTHETIC_TEST_ONLY',?,?)
            """, security, DIGEST, Timestamp.from(known));
        jdbc.update("""
            INSERT INTO golden_listing_identity(listing_id,security_id,exchange,listing_series,listed_from,identity_evidence_digest,created_at)
            VALUES (?,?,'NSE','EQ',?,?,?)
            """, listing, security, Date.valueOf(LocalDate.of(2019,1,1)), DIGEST, Timestamp.from(known));
        jdbc.update("""
            INSERT INTO golden_provider_symbol_alias(alias_id,listing_id,provider,exchange,provider_token,trading_symbol,
                valid_from,source_published_at,first_available_at,source_event_id,source_evidence_digest)
            VALUES (?,?,'ANGEL_ONE','NSE',?,?,?,?,?,?,?)
            """, UUID.randomUUID(), listing, token, symbol, Timestamp.from(known), Timestamp.from(known),
            Timestamp.from(known), "FIXTURE-" + UUID.randomUUID(), DIGEST);
        String hash = UUID.randomUUID().toString().replace("-", "") + UUID.randomUUID().toString().replace("-", "");
        jdbc.update("""
            INSERT INTO news_ingest_receipt(receipt_id,source_id,source_url,title,content_hash,published_at,first_observed_at,fetched_at)
            VALUES (?,?,?,'Synthetic E2E disclosure',?,?,?,?)
            """, receipt, source, "https://news.example.test/" + receipt, hash,
            Timestamp.from(observed.minusSeconds(30)), Timestamp.from(observed), Timestamp.from(observed));
        jdbc.update("""
            INSERT INTO news_canonical_event(event_id,event_class,status,first_seen_at,current_version)
            VALUES (?,'UNCLASSIFIED','ACTIVE',?,1)
            """, event, Timestamp.from(observed));
        jdbc.update("""
            INSERT INTO news_event_version(event_id,version,effective_known_at,category)
            VALUES (?,1,?,'UNCLASSIFIED')
            """, event, Timestamp.from(observed));
        Fixture f = new Fixture(instrument,catalogue,listing,receipt,event,source,now.minusSeconds(120),now.minusSeconds(60));
        addProof(f, 1, f.mapped(), relationship);
        return f;
    }

    private void addProof(Fixture f, int version, Instant at, String relationship) {
        jdbc.update("""
            INSERT INTO news_event_evidence(event_id,version,receipt_id,evidence_kind,observed_at)
            VALUES (?,?,?,'OTHER',?)
            """, f.event(), version, f.receipt(), Timestamp.from(at));
        jdbc.update("""
            INSERT INTO news_event_verified_listing_evidence(event_id,version,receipt_id,listing_id,relationship,
                listing_identity_digest,mapping_first_known_at,evidence_method)
            VALUES (?,?,?,?,?,?,?,'SYNTHETIC_E2E')
            """, f.event(), version, f.receipt(), f.listing(), relationship, DIGEST, Timestamp.from(at));
    }
}
