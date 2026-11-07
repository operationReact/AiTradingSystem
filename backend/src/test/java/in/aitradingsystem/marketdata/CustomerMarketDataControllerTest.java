package in.aitradingsystem.marketdata;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import in.aitradingsystem.instrument.Instrument;
import in.aitradingsystem.instrument.InstrumentMasterService;
import in.aitradingsystem.user.CustomerIdentityStore;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.server.ResponseStatusException;

class CustomerMarketDataControllerTest {
    private static final String ISSUER =
        "https://cognito-idp.ap-south-1.amazonaws.com/ap-south-1_test";
    private final CustomerIdentityStore identities = mock(CustomerIdentityStore.class);
    private final InstrumentMasterService catalogue = mock(InstrumentMasterService.class);
    private final MarketDataProvider provider = mock(MarketDataProvider.class);

    private JwtAuthenticationToken principal(String subject) {
        var jwt=Jwt.withTokenValue("signed-test-access")
            .header("alg","RS256").issuer(ISSUER).subject(subject)
            .issuedAt(Instant.parse("2026-11-07T00:00:00Z"))
            .expiresAt(Instant.parse("2026-11-08T00:00:00Z")).build();
        when(identities.find("cognito",ISSUER,subject))
            .thenReturn(Optional.of(new CustomerIdentityStore.CustomerAccount(
                UUID.randomUUID(),"ACTIVE","CUSTOMER","Test","owner@example.invalid")));
        return new JwtAuthenticationToken(jwt,
            List.of(new SimpleGrantedAuthority("ROLE_CUSTOMER")));
    }

    @Test
    void quoteFailsClosedForNonOwnerAndWithoutLambdaMode() {
        var other = principal("other-sub");
        var c = new CustomerMarketDataController(identities,catalogue,provider,
            "owner-sub","ANGEL_ONE_LAMBDA");
        assertThatThrownBy(() -> c.quote(other, 1))
            .isInstanceOfSatisfying(ResponseStatusException.class, ex ->
                assertThat(ex.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN));
        verifyNoInteractions(provider,catalogue);

        var owner=principal("owner-sub");
        var notEnabled = new CustomerMarketDataController(identities,catalogue,provider,
            "owner-sub","ANGEL_ONE");
        assertThatThrownBy(() -> notEnabled.quote(owner, 1))
            .isInstanceOfSatisfying(ResponseStatusException.class, ex ->
                assertThat(ex.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE));
        verifyNoInteractions(provider,catalogue);
    }

    @Test
    void ownerCanReadOnlyFetchVerifiedNseCashEquityQuote() {
        var c = new CustomerMarketDataController(identities,catalogue,provider,
            "owner-sub","ANGEL_ONE_LAMBDA");
        var auth=principal("owner-sub");
        var instrument = new Instrument(
            42L, "NSE", "NSE", "99999", "TEST-EQ",
            "Test", "EQ", null, null, 1, new BigDecimal("0.05"), UUID.randomUUID());
        when(catalogue.requireActiveInstrument(42L)).thenReturn(instrument);
        when(provider.lastTradedPrice(instrument)).thenReturn(new BigDecimal("125.55"));
        when(provider.source()).thenReturn("ANGEL_ONE_SMARTAPI");
        var quote = c.quote(auth,42).getBody();
        assertThat(quote).isNotNull();
        assertThat(quote.ltp()).isEqualByComparingTo("125.55");
        assertThat(quote.tradingSymbol()).isEqualTo("TEST-EQ");
        assertThat(quote.source()).isEqualTo("ANGEL_ONE_SMARTAPI");
        verify(provider).lastTradedPrice(instrument);
    }
}
