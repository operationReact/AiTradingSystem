package in.aitradingsystem.marketdata;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

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
    void missingAuthenticationCannotQueryInstrumentCatalogue() {
        assertThatThrownBy(() -> controller.universeFacets(null))
            .isInstanceOf(ResponseStatusException.class)
            .hasMessageContaining("Customer authentication required");
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
