package in.aitradingsystem.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import in.aitradingsystem.news.feature.NewsInstrumentVerifiedEvidenceService;
import in.aitradingsystem.news.feature.NewsInstrumentVerifiedEvidenceService.ResearchResult;
import in.aitradingsystem.news.feature.NewsVerifiedListingEventEvidenceRepository;
import in.aitradingsystem.support.AbstractPostgresIT;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/** Authenticated CUSTOMER read contract; all source/issuer data below are fictional. */
class CustomerMindVerifiedNewsControllerIT extends AbstractPostgresIT {
    private static final String PATH =
        "/api/customer/mind/v1/verified-news-evidence";
    private static final long INSTRUMENT = 987654L;
    private static final Instant AS_OF = Instant.parse("2026-11-24T08:00:00Z");
    private static final UUID VERSION =
        UUID.fromString("00000000-0000-0000-0000-000000000090");
    private static final UUID LISTING =
        UUID.fromString("00000000-0000-0000-0000-000000000091");

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @MockitoBean NewsInstrumentVerifiedEvidenceService verifiedNews;

    private org.springframework.test.web.servlet.request.RequestPostProcessor role(String role) {
        return SecurityMockMvcRequestPostProcessors.jwt()
            .jwt(jwt -> jwt.issuer("https://test.example").subject("verified-news-customer"))
            .authorities(new SimpleGrantedAuthority("ROLE_" + role));
    }

    @Test
    void customerGetsSourceBackedProofWithNonExecutableContract() throws Exception {
        var proof = new NewsVerifiedListingEventEvidenceRepository.Evidence(
            UUID.randomUUID(), 1, LISTING, UUID.randomUUID(),
            "fictional-source", "FICTIONAL PUBLISHER",
            "https://news.example.test/filing/1", "Fictional equity filing",
            AS_OF.minusSeconds(1800), AS_OF.minusSeconds(1200),
            AS_OF.minusSeconds(1100), AS_OF.minusSeconds(600),
            "a".repeat(64), "OTHER", "UNCLASSIFIED", false);
        when(verifiedNews.verifiedForInstrument(INSTRUMENT, AS_OF))
            .thenReturn(new ResearchResult(INSTRUMENT, AS_OF, VERSION,
                "VERIFIED_LISTING_EVENT_EVIDENCE", LISTING, 1, List.of(proof), false));

        String response = mvc.perform(get(PATH).with(role("CUSTOMER"))
                .param("instrumentId", String.valueOf(INSTRUMENT))
                .param("asOf", AS_OF.toString()))
            .andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString();

        JsonNode doc = mapper.readTree(response);
        assertThat(doc.path("contractVersion").asText())
            .isEqualTo(CustomerMindVerifiedNewsController.CONTRACT);
        assertThat(doc.path("verificationStatus").asText())
            .isEqualTo("VERIFIED_LISTING_EVENT_EVIDENCE");
        assertThat(doc.path("verifiedListingId").asText()).isEqualTo(LISTING.toString());
        assertThat(doc.path("evidenceCount").asInt()).isEqualTo(1);
        assertThat(doc.path("evidence").get(0).path("originalUrl").asText())
            .isEqualTo("https://news.example.test/filing/1");
        assertThat(doc.path("includedInDecision").asBoolean()).isFalse();
        assertThat(doc.path("canAuthorizeCapital").asBoolean()).isFalse();
        assertThat(response).doesNotContain("apiKey", "clientSecret", "BUY_CE", "SELL_PE");
    }

    @Test
    void unresolvedIssuerNeverBecomesNeutralOrFabricatedNews() throws Exception {
        when(verifiedNews.verifiedForInstrument(INSTRUMENT, AS_OF))
            .thenReturn(new ResearchResult(INSTRUMENT, AS_OF, VERSION,
                "LISTING_IDENTITY_UNVERIFIED", null, 0, List.of(), false));

        JsonNode doc = mapper.readTree(mvc.perform(get(PATH).with(role("CUSTOMER"))
                .param("instrumentId", String.valueOf(INSTRUMENT))
                .param("asOf", AS_OF.toString()))
            .andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString());
        assertThat(doc.path("verifiedListingId").isNull()).isTrue();
        assertThat(doc.path("verificationStatus").asText())
            .isEqualTo("LISTING_IDENTITY_UNVERIFIED");
        assertThat(doc.path("evidenceCount").asInt()).isZero();
        assertThat(doc.path("evidence")).isEmpty();
        assertThat(doc.path("includedInDecision").asBoolean()).isFalse();
    }

    @Test
    void customerRoleIsMandatoryAndInvalidOrFutureCutoffIsDenied() throws Exception {
        mvc.perform(get(PATH).param("instrumentId", String.valueOf(INSTRUMENT))
                .param("asOf", AS_OF.toString()))
            .andExpect(status().isUnauthorized());
        mvc.perform(get(PATH).header("X-API-Key", API_KEY)
                .param("instrumentId", String.valueOf(INSTRUMENT))
                .param("asOf", AS_OF.toString()))
            .andExpect(status().isForbidden());
        mvc.perform(get(PATH).with(role("ADMIN"))
                .param("instrumentId", String.valueOf(INSTRUMENT))
                .param("asOf", AS_OF.toString()))
            .andExpect(status().isForbidden());
        mvc.perform(get(PATH).with(role("CUSTOMER"))
                .param("instrumentId", "-1")
                .param("asOf", AS_OF.toString()))
            .andExpect(status().isBadRequest());
        mvc.perform(get(PATH).with(role("CUSTOMER"))
                .param("instrumentId", String.valueOf(INSTRUMENT))
                .param("asOf", Instant.now().plusSeconds(3600).toString()))
            .andExpect(status().isBadRequest());
    }

    @Test
    void ambiguousSourceEvidenceFailsClosedWithoutInternalDetails() throws Exception {
        when(verifiedNews.verifiedForInstrument(INSTRUMENT, AS_OF))
            .thenThrow(new IllegalStateException("Fictional ambiguous provider alias"));
        String response = mvc.perform(get(PATH).with(role("CUSTOMER"))
                .param("instrumentId", String.valueOf(INSTRUMENT))
                .param("asOf", AS_OF.toString()))
            .andExpect(status().isConflict())
            .andReturn().getResponse().getContentAsString();
        assertThat(response).doesNotContain("Fictional ambiguous provider alias");
    }
}
