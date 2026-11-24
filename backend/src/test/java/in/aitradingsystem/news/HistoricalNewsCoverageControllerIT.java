package in.aitradingsystem.news;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import in.aitradingsystem.news.feature.HistoricalNewsCoverageService;
import in.aitradingsystem.news.feature.HistoricalNewsCoverageService.CoveragePage;
import in.aitradingsystem.news.feature.HistoricalNewsCoverageService.CoverageRow;
import in.aitradingsystem.support.AbstractPostgresIT;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

class HistoricalNewsCoverageControllerIT extends AbstractPostgresIT {
    private static final String PATH = "/api/admin/market-intelligence/historical-coverage";
    private static final Instant AT = Instant.parse("2026-11-01T08:00:00Z");
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @MockitoBean HistoricalNewsCoverageService coverage;

    private RequestPostProcessor role(String name) {
        return jwt().jwt(j -> j.issuer("https://test.example").subject("coverage-it"))
            .authorities(new SimpleGrantedAuthority("ROLE_" + name));
    }

    @Test
    void requiresAdminAndRejectsMalformedFingerprint() throws Exception {
        mvc.perform(get(PATH).param("asOf", AT.toString())).andExpect(status().isUnauthorized());
        mvc.perform(get(PATH).header("X-API-Key", API_KEY).param("asOf", AT.toString()))
            .andExpect(status().isForbidden());
        mvc.perform(get(PATH).with(role("CUSTOMER")).param("asOf", AT.toString()))
            .andExpect(status().isForbidden());
        mvc.perform(get(PATH).with(role("ADMIN")).param("asOf", AT.toString())
            .param("expectedFingerprint", "not-a-digest")).andExpect(status().isBadRequest());
        verifyNoInteractions(coverage);
    }

    @Test
    void reportsMeasuredPageWithoutClaimingCompleteUniverse() throws Exception {
        UUID version = UUID.randomUUID();
        String fingerprint = "a".repeat(64);
        when(coverage.measure(eq(AT), eq(0), eq(25), isNull(), isNull()))
            .thenReturn(new CoveragePage(version, fingerprint, AT, 100, 0, 1, 1,
                0, 0, 1, 0, false, false,
                List.of(new CoverageRow(123, "NSE", "LISTING_IDENTITY_UNVERIFIED", 0))));
        String raw = mvc.perform(get(PATH).with(role("ADMIN")).param("asOf", AT.toString()))
            .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        var body = mapper.readTree(raw);
        assertThat(body.path("catalogueVersion").asText()).isEqualTo(version.toString());
        assertThat(body.path("catalogueFingerprint").asText()).isEqualTo(fingerprint);
        assertThat(body.path("totalCatalogueInstruments").asInt()).isEqualTo(100);
        assertThat(body.path("measuredInstruments").asInt()).isEqualTo(1);
        assertThat(body.path("unverifiedIdentity").asInt()).isEqualTo(1);
        assertThat(body.path("completeUniverseInThisPage").asBoolean()).isFalse();
        assertThat(body.path("canAuthorizeCapital").asBoolean()).isFalse();
    }

    @Test
    void unsafeMeasurementIsConflictNotInventedCoverage() throws Exception {
        when(coverage.measure(eq(AT), eq(0), eq(25), isNull(), isNull()))
            .thenThrow(new IllegalStateException("internal detail"));
        String raw = mvc.perform(get(PATH).with(role("ADMIN")).param("asOf", AT.toString()))
            .andExpect(status().isConflict()).andReturn().getResponse().getContentAsString();
        assertThat(raw).doesNotContain("internal detail");
    }
}
