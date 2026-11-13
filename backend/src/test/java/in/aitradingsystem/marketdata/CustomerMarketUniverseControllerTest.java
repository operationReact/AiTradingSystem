package in.aitradingsystem.marketdata;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import in.aitradingsystem.instrument.InstrumentMasterRepository;
import in.aitradingsystem.user.CustomerIdentityStore;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.server.ResponseStatusException;

class CustomerMarketUniverseControllerTest {
    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final CustomerIdentityStore identities = mock(CustomerIdentityStore.class);
    private final CustomerMarketUniverseController controller =
        new CustomerMarketUniverseController(jdbc, identities);

    @Test
    void catalogueTotalsAreNotCappedAtNifty500OrTotalMarket() {
        assertThat(CustomerMarketUniverseController.countActiveListings(List.of(
            new CustomerMarketUniverseController.ExchangeFacet("NSE", 2801),
            new CustomerMarketUniverseController.ExchangeFacet("BSE", 3208))))
            .isEqualTo(6009);
        assertThat(CustomerMarketUniverseController.countActiveListings(List.of()))
            .isZero();
    }

    @Test
    void fullMarketCoverageCountsSourceBackedQuotesAcrossBothExchanges() {
        var coverage = CustomerMarketUniverseController.summarizeFullQuotes(List.of(
            new CustomerMarketUniverseController.ExchangeQuoteCoverage(
                "NSE", 2801, 875, 800, Instant.parse("2026-11-12T09:59:00Z")),
            new CustomerMarketUniverseController.ExchangeQuoteCoverage(
                "BSE", 3208, 50, 25, Instant.parse("2026-11-12T09:58:00Z"))
        ), 3500);
        assertThat(coverage.totalActiveListings()).isEqualTo(6009);
        assertThat(coverage.storedFullQuotes()).isEqualTo(925);
        assertThat(coverage.withProviderPreviousClose()).isEqualTo(825);
        assertThat(coverage.activeBseCmRecords()).isEqualTo(3500);
        assertThat(coverage.exchanges()).extracting("exchange")
            .containsExactly("NSE", "BSE");
        assertThat(coverage.contractVersion()).isEqualTo("customer.market.full-quote-coverage.v1");
    }

    @Test
    void absentBseDataIsZeroCoverageAndNeverMadeUp() {
        var coverage = CustomerMarketUniverseController.summarizeFullQuotes(List.of(
            new CustomerMarketUniverseController.ExchangeQuoteCoverage(
                "NSE", 2756, 50, 48, Instant.parse("2026-11-12T09:59:00Z"))
        ), 0);
        assertThat(coverage.totalActiveListings()).isEqualTo(2756);
        assertThat(coverage.exchanges().get(1).activeListings()).isZero();
        assertThat(coverage.exchanges().get(1).storedFullQuotes()).isZero();
        assertThat(coverage.storedFullQuotes()).isEqualTo(50);
    }

    @Test
    void invalidOrOvercountedFullQuoteCoverageIsRejected() {
        assertThatThrownBy(() -> CustomerMarketUniverseController.summarizeFullQuotes(
            List.of(new CustomerMarketUniverseController.ExchangeQuoteCoverage(
                "NSE", 50, 51, 30, Instant.now())), 0))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> CustomerMarketUniverseController.summarizeFullQuotes(
            List.of(new CustomerMarketUniverseController.ExchangeQuoteCoverage(
                "NSE", 50, 40, 41, Instant.now())), 0))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> CustomerMarketUniverseController.summarizeFullQuotes(
            List.of(new CustomerMarketUniverseController.ExchangeQuoteCoverage(
                "NSE", 50, 40, 30, Instant.now()), new CustomerMarketUniverseController.ExchangeQuoteCoverage(
                "NSE", 50, 40, 30, Instant.now())), 0))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void cashEligibilityIncludesBseEquitiesWithoutNseSuffix() {
        // Angel One parser tests document BSE RELIANCE with instrumentType EQ
        // and no -EQ suffix. Counts and search must use this shared predicate.
        String rule = InstrumentMasterRepository.CASH_EQUITY_PREDICATE;
        assertThat(rule).contains("i.exchange_segment = 'BSE_CM'");
        assertThat(rule).contains("upper(coalesce(i.instrument_type, '')) = 'EQ'");
        assertThat(rule).contains("i.exchange_segment = 'NSE_CM'");
        assertThat(rule).contains("i.trading_symbol LIKE '%-EQ'");
        assertThat(rule).doesNotContain("LIMIT 500", "LIMIT 750");
    }

    @Test
    void missingAuthenticationCannotQueryInstrumentCatalogue() {
        assertThatThrownBy(() -> controller.universeFacets(null))
            .isInstanceOf(ResponseStatusException.class)
            .hasMessageContaining("Customer authentication required");
        verifyNoInteractions(jdbc);
    }

    @Test
    void fullQuoteCoverageRejectsUnauthenticatedRequestsBeforeDatabaseAccess() {
        assertThatThrownBy(() -> controller.fullQuoteCoverage(null))
            .isInstanceOf(ResponseStatusException.class)
            .hasMessageContaining("Customer authentication required");
        assertThatThrownBy(() -> controller.fullQuoteCoverage(principal("ROLE_OPERATOR")))
            .isInstanceOf(ResponseStatusException.class);
        verifyNoInteractions(jdbc);
    }

    @Test
    void nonCustomerRoleCannotQueryInstrumentCatalogue() {
        var token = principal("ROLE_OPERATOR");
        assertThatThrownBy(() -> controller.universeFacets(token))
            .isInstanceOf(ResponseStatusException.class);
        verifyNoInteractions(jdbc);
    }

    @Test
    void customerJwtWithoutLinkedActiveIdentityCannotQueryCatalogue() {
        var token = principal("ROLE_CUSTOMER");
        String issuer = "https://cognito-idp.ap-south-1.amazonaws.com/ap-south-1_test";
        when(identities.find("cognito", issuer, "subject-test"))
            .thenReturn(Optional.empty());
        assertThatThrownBy(() -> controller.universeFacets(token))
            .isInstanceOf(ResponseStatusException.class)
            .hasMessageContaining("Customer identity not linked");
        verifyNoInteractions(jdbc);
    }

    private static JwtAuthenticationToken principal(String role) {
        var jwt = Jwt.withTokenValue("signed-synthetic-test")
            .header("alg", "RS256")
            .issuer("https://cognito-idp.ap-south-1.amazonaws.com/ap-south-1_test")
            .subject("subject-test")
            .issuedAt(Instant.parse("2026-11-11T00:00:00Z"))
            .expiresAt(Instant.parse("2026-11-12T00:00:00Z"))
            .build();
        return new JwtAuthenticationToken(jwt,
            List.of(new SimpleGrantedAuthority(role)));
    }
}
