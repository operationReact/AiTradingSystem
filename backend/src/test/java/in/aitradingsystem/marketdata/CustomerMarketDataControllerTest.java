package in.aitradingsystem.marketdata;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import in.aitradingsystem.instrument.Instrument;
import in.aitradingsystem.provider.angelone.CustomerAngelOneSessionService;
import in.aitradingsystem.provider.angelone.CustomerAngelOneLiveQuoteService;
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
    void connectedCustomerCanReadOwnVerifiedCashQuoteWithoutAccessingOwnerFeed() {
        var broker = mock(CustomerAngelOneSessionService.class);
        var auth = principal("other-sub");
        UUID accountId = UUID.randomUUID();
        when(identities.find("cognito", ISSUER, "other-sub"))
            .thenReturn(Optional.of(new CustomerIdentityStore.CustomerAccount(
                accountId, "ACTIVE", "CUSTOMER", "Trader", "customer@example.invalid")));
        when(broker.status(accountId)).thenReturn(
            new CustomerAngelOneSessionService.ConnectionStatus(
                true, "ANGEL_ONE", "***123", Instant.now().plusSeconds(3600), false));
        var instrument = new Instrument(
            42L, "NSE_CM", "NSE", "99999", "TEST-EQ",
            "Test", "EQ", null, null, 1, new BigDecimal("0.05"), UUID.randomUUID());
        when(catalogue.requireActiveInstrument(42L)).thenReturn(instrument);
        when(broker.quote(accountId, instrument)).thenReturn(new BigDecimal("125.55"));
        var controller = new CustomerMarketDataController(
            identities, catalogue, provider, "owner-sub", "ANGEL_ONE_LAMBDA", broker);
        var result = controller.quote(auth, 42L).getBody();
        assertThat(result).isNotNull();
        assertThat(result.ltp()).isEqualByComparingTo("125.55");
        assertThat(result.source()).isEqualTo("ANGEL_ONE_SMARTAPI_CUSTOMER");
        verify(broker).quote(accountId, instrument);
        verifyNoInteractions(provider);
    }

    @Test
    void ownerCanReadOnlyFetchVerifiedNseCashEquityQuote() {
        var c = new CustomerMarketDataController(identities,catalogue,provider,
            "owner-sub","ANGEL_ONE_LAMBDA");
        var auth=principal("owner-sub");
        var instrument = new Instrument(
            42L, "NSE_CM", "NSE", "99999", "TEST-EQ",
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
    @Test
    void liveQuoteNeverFallsBackToRestPriceOrExposesBrokerTokens() {
        var broker = mock(CustomerAngelOneSessionService.class);
        var streaming = mock(CustomerAngelOneLiveQuoteService.class);
        var auth = principal("customer-sub");
        UUID accountId = UUID.randomUUID();
        when(identities.find("cognito", ISSUER, "customer-sub"))
            .thenReturn(Optional.of(new CustomerIdentityStore.CustomerAccount(
                accountId, "ACTIVE", "CUSTOMER", "Test", "customer@example.invalid")));
        when(broker.status(accountId)).thenReturn(
            new CustomerAngelOneSessionService.ConnectionStatus(
                true, "ANGEL_ONE", "***123", Instant.now().plusSeconds(3600), false));
        var instrument = new Instrument(42L, "NSE_CM", "NSE", "private-token",
            "TEST-EQ", "Test", "EQ", null, null, 1, new BigDecimal("0.05"), UUID.randomUUID());
        when(catalogue.requireActiveInstrument(42L)).thenReturn(instrument);
        when(streaming.quote(accountId, instrument)).thenReturn(
            new CustomerAngelOneLiveQuoteService.LiveQuote(42L, "NSE", "TEST-EQ",
                new BigDecimal("125.55"), Instant.parse("2026-11-08T09:00:00Z"),
                Instant.parse("2026-11-08T09:00:01Z"), "LIVE", "ANGEL_ONE_SMARTAPI_WS_V2"));
        var controller = new CustomerMarketDataController(
            identities, catalogue, provider, "owner-sub", "ANGEL_ONE_LAMBDA", broker, streaming);
        var result = controller.quote(auth, 42L, true).getBody();
        assertThat(result).isNotNull();
        assertThat(result.status()).isEqualTo("LIVE");
        assertThat(result.exchangeAt()).isEqualTo(Instant.parse("2026-11-08T09:00:00Z"));
        assertThat(result.source()).isEqualTo("ANGEL_ONE_SMARTAPI_WS_V2");
        assertThat(result.ltp()).isEqualByComparingTo("125.55");
        assertThat(result.toString()).doesNotContain("private-token");
        verify(streaming).quote(accountId, instrument);
        verify(broker, never()).quote(accountId, instrument);
        verifyNoInteractions(provider);
    }

    @Test
    void liveQuoteWithoutManualBrokerSessionIsUnavailableEvenToOwner() {
        var auth = principal("owner-sub");
        var broker = mock(CustomerAngelOneSessionService.class);
        var streaming = mock(CustomerAngelOneLiveQuoteService.class);
        var instrument = new Instrument(42L, "NSE_CM", "NSE", "99999",
            "TEST-EQ", "Test", "EQ", null, null, 1, new BigDecimal("0.05"), UUID.randomUUID());
        when(catalogue.requireActiveInstrument(42L)).thenReturn(instrument);
        var controller = new CustomerMarketDataController(
            identities, catalogue, provider, "owner-sub", "ANGEL_ONE_LAMBDA", broker, streaming);
        assertThatThrownBy(() -> controller.quote(auth, 42L, true))
            .isInstanceOfSatisfying(ResponseStatusException.class,
                ex -> assertThat(ex.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE));
        verifyNoInteractions(streaming, provider);
    }

    @Test
    void expiredManualBrokerSessionIsUnavailableWithoutCallingLiveOrRestProviders() {
        var auth = principal("customer-expired");
        var broker = mock(CustomerAngelOneSessionService.class);
        var streaming = mock(CustomerAngelOneLiveQuoteService.class);
        UUID accountId = UUID.randomUUID();
        when(identities.find("cognito", ISSUER, "customer-expired"))
            .thenReturn(Optional.of(new CustomerIdentityStore.CustomerAccount(
                accountId, "ACTIVE", "CUSTOMER", "Test", "customer@example.invalid")));
        when(broker.status(accountId)).thenReturn(
            new CustomerAngelOneSessionService.ConnectionStatus(
                true, "ANGEL_ONE", "***123", Instant.now().minusSeconds(60), false));
        var controller = new CustomerMarketDataController(identities, catalogue,
            provider, "owner-sub", "ANGEL_ONE_LAMBDA", broker, streaming);
        assertThatThrownBy(() -> controller.quote(auth, 42L, true))
            .isInstanceOfSatisfying(ResponseStatusException.class, ex ->
                assertThat(ex.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN));
        verifyNoInteractions(streaming, provider, catalogue);
    }

    @Test
    void customerLiveQuoteRejectsMismatchedInstrumentIdentityAndInventedLiveSource() {
        var auth = principal("customer-identity");
        var broker = mock(CustomerAngelOneSessionService.class);
        var streaming = mock(CustomerAngelOneLiveQuoteService.class);
        UUID accountId = UUID.randomUUID();
        when(identities.find("cognito", ISSUER, "customer-identity"))
            .thenReturn(Optional.of(new CustomerIdentityStore.CustomerAccount(
                accountId, "ACTIVE", "CUSTOMER", "Test", "customer@example.invalid")));
        when(broker.status(accountId)).thenReturn(
            new CustomerAngelOneSessionService.ConnectionStatus(
                true, "ANGEL_ONE", "***123", Instant.now().plusSeconds(3600), false));
        var instrument = new Instrument(42L, "NSE_CM", "NSE", "private-token",
            "TEST-EQ", "Test", "EQ", null, null, 1,
            new BigDecimal("0.05"), UUID.randomUUID());
        when(catalogue.requireActiveInstrument(42L)).thenReturn(instrument);
        var controller = new CustomerMarketDataController(identities, catalogue,
            provider, "owner-sub", "ANGEL_ONE_LAMBDA", broker, streaming);

        when(streaming.quote(accountId, instrument)).thenReturn(
            new CustomerAngelOneLiveQuoteService.LiveQuote(43L, "BSE", "OTHER-EQ",
                new BigDecimal("125.55"), Instant.parse("2026-11-08T09:00:00Z"),
                Instant.parse("2026-11-08T09:00:01Z"), "LIVE",
                "ANGEL_ONE_SMARTAPI_WS_V2"));
        assertThatThrownBy(() -> controller.quote(auth, 42L, true))
            .isInstanceOfSatisfying(ResponseStatusException.class, ex ->
                assertThat(ex.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE));

        when(streaming.quote(accountId, instrument)).thenReturn(
            new CustomerAngelOneLiveQuoteService.LiveQuote(42L, "NSE", "TEST-EQ",
                new BigDecimal("125.55"), Instant.parse("2026-11-08T09:00:00Z"),
                Instant.parse("2026-11-08T09:00:01Z"), "LIVE", "SIMULATED"));
        assertThatThrownBy(() -> controller.quote(auth, 42L, true))
            .isInstanceOfSatisfying(ResponseStatusException.class, ex ->
                assertThat(ex.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE));
        verifyNoInteractions(provider);
        verify(broker, never()).quote(accountId, instrument);
    }


    @Test
    void ownerOnDemandQuoteUsesEipSessionInsteadOfLegacyLambda() {
        var broker = mock(CustomerAngelOneSessionService.class);
        var auth = principal("owner-sub");
        UUID accountId = UUID.randomUUID();
        when(identities.find("cognito", ISSUER, "owner-sub"))
            .thenReturn(Optional.of(new CustomerIdentityStore.CustomerAccount(
                accountId, "ACTIVE", "CUSTOMER", "Test", "owner@example.invalid")));
        when(broker.status(accountId)).thenReturn(
            new CustomerAngelOneSessionService.ConnectionStatus(
                true, "ANGEL_ONE", "***234", Instant.now().plusSeconds(3600), false));
        var instrument = new Instrument(42L, "NSE_CM", "NSE", "99999",
            "TEST-EQ", "Test", "EQ", null, null, 1,
            new BigDecimal("0.05"), UUID.randomUUID());
        when(catalogue.requireActiveInstrument(42L)).thenReturn(instrument);
        when(broker.quote(accountId, instrument)).thenReturn(new BigDecimal("150.40"));
        var controller = new CustomerMarketDataController(identities, catalogue,
            provider, "owner-sub", "ANGEL_ONE_LAMBDA", broker);
        var result = controller.quote(auth, 42L).getBody();
        assertThat(result.source()).isEqualTo("ANGEL_ONE_SMARTAPI_CUSTOMER");
        assertThat(result.ltp()).isEqualByComparingTo("150.40");
        verify(broker).quote(accountId, instrument);
        verifyNoInteractions(provider);
    }
}
