package in.aitradingsystem.marketdata;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import in.aitradingsystem.user.CustomerIdentityStore;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.server.ResponseStatusException;

class CustomerMarketCandleControllerTest {
    private static final String ISSUER =
        "https://cognito-idp.ap-south-1.amazonaws.com/ap-south-1_test";
    private final CustomerIdentityStore identities = mock(CustomerIdentityStore.class);
    private final HistoricalCandleService candles = mock(HistoricalCandleService.class);

    private JwtAuthenticationToken principal(String subject, String role) {
        var jwt = Jwt.withTokenValue("signed-test-access")
            .header("alg", "RS256").issuer(ISSUER).subject(subject)
            .issuedAt(Instant.parse("2026-11-07T00:00:00Z"))
            .expiresAt(Instant.parse("2026-11-08T00:00:00Z")).build();
        when(identities.find("cognito", ISSUER, subject))
            .thenReturn(Optional.of(new CustomerIdentityStore.CustomerAccount(
                UUID.randomUUID(), "ACTIVE", role, "Test", "customer@example.invalid")));
        return new JwtAuthenticationToken(jwt,
            List.of(new SimpleGrantedAuthority("ROLE_CUSTOMER")));
    }

    private CustomerMarketCandleController controller() {
        return new CustomerMarketCandleController(identities, candles);
    }

    @Test
    void returnsStoredCandlesWithoutBrokerSymbolToken() {
        var customer = principal("independent-customer", "CUSTOMER");
        var fetched = Instant.parse("2026-11-06T10:00:00Z");
        var bar = new MarketCandle(7L, 42L, "NSE", "TEST-EQ", "99999", UUID.randomUUID(),
            "ONE_DAY", Instant.parse("2026-11-05T18:30:00Z"),
            new BigDecimal("100.00"), new BigDecimal("110.00"), new BigDecimal("95.00"),
            new BigDecimal("105.50"), 12_345L, "ANGEL_ONE_SMARTAPI", fetched);
        when(candles.query(42L, HistoricalCandleInterval.ONE_DAY,
            LocalDate.of(2026, 10, 1), LocalDate.of(2026, 11, 6), 0, 200))
            .thenReturn(new CandlePage(List.of(bar), 0, 200, 1, 1, false));

        var response = controller().candles(customer, 42L, HistoricalCandleInterval.ONE_DAY,
            LocalDate.of(2026, 10, 1), LocalDate.of(2026, 11, 6), 0, 200).getBody();

        assertThat(response).isNotNull();
        assertThat(response.instrumentId()).isEqualTo(42L);
        assertThat(response.interval()).isEqualTo("ONE_DAY");
        assertThat(response.totalItems()).isEqualTo(1);
        assertThat(response.hasNext()).isFalse();
        var item = response.items().get(0);
        assertThat(item.candleTime()).isEqualTo(bar.candleTime());
        assertThat(item.close()).isEqualByComparingTo("105.50");
        assertThat(item.volume()).isEqualTo(12_345L);
        assertThat(item.source()).isEqualTo("ANGEL_ONE_SMARTAPI");
        assertThat(item.fetchedAt()).isEqualTo(fetched);
        assertThat(CustomerMarketCandleController.CustomerCandle.class.getRecordComponents())
            .extracting(c -> c.getName())
            .doesNotContain("symbolToken", "tradingSymbol", "catalogueVersion", "id");
    }

    @Test
    void rejectsNonCustomerPrincipals() {
        var operator = principal("operator-subject", "OPERATOR");
        assertThatThrownBy(() -> controller().candles(operator, 42L, HistoricalCandleInterval.ONE_DAY,
            LocalDate.of(2026, 10, 1), LocalDate.of(2026, 11, 6), 0, 200))
            .isInstanceOf(ResponseStatusException.class)
            .satisfies(ex -> assertThat(((ResponseStatusException) ex).getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED));
        verify(candles, never()).query(anyLong(), any(), any(), any(), anyInt(), anyInt());
    }
}
