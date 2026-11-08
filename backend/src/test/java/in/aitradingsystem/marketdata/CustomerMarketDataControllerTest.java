package in.aitradingsystem.marketdata;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import in.aitradingsystem.instrument.Instrument;
import in.aitradingsystem.instrument.InstrumentMasterService;
import in.aitradingsystem.provider.angelone.AppAngelOneMarketFeedService;
import in.aitradingsystem.provider.angelone.CustomerAngelOneLiveQuoteService;
import in.aitradingsystem.provider.angelone.CustomerAngelOneSessionService;
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

/** Market data uses the APPLICATION's protected feed, not a user's broker session. */
class CustomerMarketDataControllerTest {
    private static final String ISSUER =
        "https://cognito-idp.ap-south-1.amazonaws.com/ap-south-1_test";
    private final CustomerIdentityStore identities = mock(CustomerIdentityStore.class);
    private final InstrumentMasterService catalogue = mock(InstrumentMasterService.class);
    private final MarketDataProvider provider = mock(MarketDataProvider.class);
    private final CustomerAngelOneSessionService customerBroker =
        mock(CustomerAngelOneSessionService.class);
    private final CustomerAngelOneLiveQuoteService personalSockets =
        mock(CustomerAngelOneLiveQuoteService.class);
    private final AppAngelOneMarketFeedService applicationFeed =
        mock(AppAngelOneMarketFeedService.class);

    private JwtAuthenticationToken principal(String subject) {
        var jwt = Jwt.withTokenValue("signed-test-access")
            .header("alg", "RS256").issuer(ISSUER).subject(subject)
            .issuedAt(Instant.parse("2026-11-07T00:00:00Z"))
            .expiresAt(Instant.parse("2026-11-08T00:00:00Z")).build();
        when(identities.find("cognito", ISSUER, subject))
            .thenReturn(Optional.of(new CustomerIdentityStore.CustomerAccount(
                UUID.randomUUID(), "ACTIVE", "CUSTOMER",
                "Test", "customer@example.invalid")));
        return new JwtAuthenticationToken(jwt,
            List.of(new SimpleGrantedAuthority("ROLE_CUSTOMER")));
    }

    private Instrument equity() {
        return new Instrument(42L, "NSE_CM", "NSE", "99999", "TEST-EQ",
            "Test", "EQ", null, null, 1,
            new BigDecimal("0.05"), UUID.randomUUID());
    }

    private CustomerMarketDataController controller() {
        return new CustomerMarketDataController(identities, catalogue, provider,
            "unrelated-owner-sub", "ANGEL_ONE", customerBroker,
            personalSockets, applicationFeed);
    }

    @Test
    void appFeedQuotesAreAvailableWithoutPersonalBrokerConnection() {
        var customer = principal("independent-customer");
        var instrument = equity();
        when(catalogue.requireActiveInstrument(42L)).thenReturn(instrument);
        when(applicationFeed.available()).thenReturn(true);
        when(applicationFeed.quote(instrument)).thenReturn(new BigDecimal("125.55"));

        var response = controller().quote(customer, 42L).getBody();

        assertThat(response).isNotNull();
        assertThat(response.ltp()).isEqualByComparingTo("125.55");
        assertThat(response.source()).isEqualTo("ANGEL_ONE_SMARTAPI_APP");
        assertThat(response.tradingSymbol()).isEqualTo("TEST-EQ");
        verify(applicationFeed).quote(instrument);
        verifyNoInteractions(customerBroker, personalSockets, provider);
    }

    @Test
    void livePriceComesFromTheSameApplicationFeedNotBrokerSession() {
        var customer = principal("independent-customer");
        var instrument = equity();
        when(catalogue.requireActiveInstrument(42L)).thenReturn(instrument);
        when(applicationFeed.available()).thenReturn(true);
        when(applicationFeed.live(instrument)).thenReturn(
            new CustomerAngelOneLiveQuoteService.LiveQuote(42L, "NSE", "TEST-EQ",
                new BigDecimal("155.30"),
                Instant.parse("2026-11-08T09:00:00Z"),
                Instant.parse("2026-11-08T09:00:01Z"),
                "LIVE", "ANGEL_ONE_SMARTAPI_WS_V2"));

        var response = controller().quote(customer, 42L, true).getBody();

        assertThat(response).isNotNull();
        assertThat(response.status()).isEqualTo("LIVE");
        assertThat(response.ltp()).isEqualByComparingTo("155.30");
        assertThat(response.source()).isEqualTo("ANGEL_ONE_SMARTAPI_WS_V2");
        verifyNoInteractions(customerBroker, personalSockets, provider);
    }

    @Test
    void closedMarketDoesNotInventLivePrices() {
        var customer = principal("independent-customer");
        var instrument = equity();
        when(catalogue.requireActiveInstrument(42L)).thenReturn(instrument);
        when(applicationFeed.available()).thenReturn(true);
        when(applicationFeed.live(instrument)).thenReturn(
            new CustomerAngelOneLiveQuoteService.LiveQuote(42L, "NSE", "TEST-EQ",
                null, null, null, "MARKET_CLOSED", null));
        var response = controller().quote(customer, 42L, true).getBody();
        assertThat(response.status()).isEqualTo("MARKET_CLOSED");
        assertThat(response.ltp()).isNull();
        assertThat(response.exchangeAt()).isNull();
        verifyNoInteractions(customerBroker, personalSockets);
    }

    @Test
    void spoofedTickOrSourceIsRejectedRatherThanRenderedLive() {
        var customer = principal("independent-customer");
        var instrument = equity();
        when(catalogue.requireActiveInstrument(42L)).thenReturn(instrument);
        when(applicationFeed.available()).thenReturn(true);
        when(applicationFeed.live(instrument)).thenReturn(
            new CustomerAngelOneLiveQuoteService.LiveQuote(999L, "BSE", "OTHER-EQ",
                new BigDecimal("999.00"),
                Instant.parse("2026-11-08T09:00:00Z"),
                Instant.parse("2026-11-08T09:00:01Z"),
                "LIVE", "SIMULATED"));
        assertThatThrownBy(() -> controller().quote(customer, 42L, true))
            .isInstanceOfSatisfying(ResponseStatusException.class, ex ->
                assertThat(ex.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE));
        verifyNoInteractions(customerBroker, personalSockets, provider);
    }

    @Test
    void unsupportedInstrumentIsRejectedBeforeAnyProviderCall() {
        var customer = principal("independent-customer");
        var derivative = new Instrument(51L, "NFO", "NFO", "99999", "TEST-FUT",
            "Test", "FUT", null, null, 1,
            new BigDecimal("0.05"), UUID.randomUUID());
        when(catalogue.requireActiveInstrument(51L)).thenReturn(derivative);
        assertThatThrownBy(() -> controller().quote(customer, 51L))
            .isInstanceOfSatisfying(ResponseStatusException.class, ex ->
                assertThat(ex.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST));
        verifyNoInteractions(applicationFeed, personalSockets, customerBroker, provider);
    }

    @Test
    void lambdaApplicationFeedCanProvideOnDemandQuotesWithoutUserBroker() {
        var customer = principal("independent-customer");
        var instrument = equity();
        when(catalogue.requireActiveInstrument(42L)).thenReturn(instrument);
        when(applicationFeed.available()).thenReturn(false);
        when(provider.lastTradedPrice(instrument)).thenReturn(new BigDecimal("88.90"));
        when(provider.source()).thenReturn("ANGEL_ONE_SMARTAPI");
        var controller = new CustomerMarketDataController(identities, catalogue, provider,
            "", "ANGEL_ONE_LAMBDA", customerBroker, personalSockets, applicationFeed);
        var quote = controller.quote(customer, 42L).getBody();
        assertThat(quote.ltp()).isEqualByComparingTo("88.90");
        verifyNoInteractions(customerBroker, personalSockets);
    }

    @Test
    void missingApplicationFeedIs503NotDemandToConnectPersonalBroker() {
        var customer = principal("independent-customer");
        var instrument = equity();
        when(catalogue.requireActiveInstrument(42L)).thenReturn(instrument);
        when(applicationFeed.available()).thenReturn(false);
        assertThatThrownBy(() -> controller().quote(customer, 42L))
            .isInstanceOfSatisfying(ResponseStatusException.class, ex ->
                assertThat(ex.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE));
        assertThatThrownBy(() -> controller().quote(customer, 42L, true))
            .isInstanceOfSatisfying(ResponseStatusException.class, ex ->
                assertThat(ex.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE));
        verifyNoInteractions(customerBroker, personalSockets);
    }
}
