package in.aitradingsystem.marketdata;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import in.aitradingsystem.instrument.Instrument;
import in.aitradingsystem.instrument.InstrumentMasterService;
import in.aitradingsystem.provider.angelone.AngelOneQuoteDetailParser;
import in.aitradingsystem.provider.angelone.AppAngelOneMarketFeedService;
import in.aitradingsystem.provider.angelone.CustomerAngelOneLiveQuoteService;
import in.aitradingsystem.provider.angelone.CustomerAngelOneSessionService;
import in.aitradingsystem.user.CustomerIdentityStore;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
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
    void snapshotPricesOnlyVerifiedLiveRowsAndMarksMismatchesUnavailable() {
        var customer = principal("independent-customer");
        var live = equity();
        var mismatched = new Instrument(43L, "NSE_CM", "NSE", "88888", "OTHER-EQ",
            "Other", "EQ", null, null, 1, new BigDecimal("0.05"), UUID.randomUUID());
        when(catalogue.requireActiveInstrument(42L)).thenReturn(live);
        when(catalogue.requireActiveInstrument(43L)).thenReturn(mismatched);
        when(applicationFeed.available()).thenReturn(true);
        when(applicationFeed.snapshot(List.of(live, mismatched))).thenReturn(List.of(
            new CustomerAngelOneLiveQuoteService.LiveQuote(42L, "NSE", "TEST-EQ",
                new BigDecimal("155.30"),
                Instant.parse("2026-11-08T09:00:00Z"),
                Instant.parse("2026-11-08T09:00:01Z"),
                "LIVE", "ANGEL_ONE_SMARTAPI_WS_V2"),
            new CustomerAngelOneLiveQuoteService.LiveQuote(999L, "NSE", "WRONG-EQ",
                new BigDecimal("10.00"), null, null, "LIVE", "ANGEL_ONE_SMARTAPI_WS_V2")));

        var store = mock(MarketLastTickStore.class);
        var controller = new CustomerMarketDataController(identities, catalogue, provider,
            "unrelated-owner-sub", "ANGEL_ONE", customerBroker, personalSockets, applicationFeed, null, store);
        var snapshot = controller.snapshot(customer, List.of(42L, 43L)).getBody();

        assertThat(snapshot).isNotNull();
        assertThat(snapshot.rows()).hasSize(2);
        assertThat(snapshot.rows().get(0).status()).isEqualTo("LIVE");
        assertThat(snapshot.rows().get(0).ltp()).isEqualByComparingTo("155.30");
        assertThat(snapshot.rows().get(1).status()).isEqualTo("UNAVAILABLE");
        assertThat(snapshot.rows().get(1).ltp()).isNull();
        verify(store).recordLive(argThat(quotes -> quotes.size() == 1
            && quotes.iterator().next().instrumentId() == 42L
            && "ANGEL_ONE_SMARTAPI_WS_V2".equals(quotes.iterator().next().source())));
        verifyNoInteractions(customerBroker, personalSockets, provider);
    }

    @Test
    void snapshotRejectsDuplicatesOversizeAndNonCashInstruments() {
        var customer = principal("independent-customer");
        var controller = controller();
        assertThatThrownBy(() -> controller.snapshot(customer, List.of(42L, 42L)))
            .isInstanceOf(ResponseStatusException.class);
        assertThatThrownBy(() -> controller.snapshot(customer,
                java.util.stream.LongStream.rangeClosed(1, 31).boxed().toList()))
            .isInstanceOf(ResponseStatusException.class);
        var future = new Instrument(44L, "NSE_FO", "NFO", "77777", "TEST24NOVFUT",
            "Test", "FUT", null, null, 1, new BigDecimal("0.05"), UUID.randomUUID());
        when(catalogue.requireActiveInstrument(44L)).thenReturn(future);
        assertThatThrownBy(() -> controller.snapshot(customer, List.of(44L)))
            .isInstanceOf(ResponseStatusException.class);
        verifyNoInteractions(applicationFeed);
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
    void detailQuoteUsesOnlyTheApplicationFeedAndReturnsOhlcWithProvenance() {
        var customer = principal("independent-customer");
        var instrument = equity();
        when(catalogue.requireActiveInstrument(42L)).thenReturn(instrument);
        when(applicationFeed.available()).thenReturn(true);
        when(applicationFeed.detail(instrument)).thenReturn(new AngelOneQuoteDetailParser.Detail(
            new BigDecimal("125.55"), new BigDecimal("120.00"), new BigDecimal("126.00"),
            new BigDecimal("119.50"), new BigDecimal("121.00"), 987654L,
            Instant.parse("2026-11-08T09:00:00Z"), new BigDecimal("123.40"), new BigDecimal("150.00"),
            new BigDecimal("100.10"), 500L, 450L,
            List.of(new AngelOneQuoteDetailParser.DepthLevel(new BigDecimal("125.50"), 300L, 4L)),
            List.of(new AngelOneQuoteDetailParser.DepthLevel(new BigDecimal("125.60"), 200L, 2L))));

        var response = controller().quoteDetail(customer, 42L).getBody();

        assertThat(response).isNotNull();
        assertThat(response.ltp()).isEqualByComparingTo("125.55");
        assertThat(response.open()).isEqualByComparingTo("120.00");
        assertThat(response.high()).isEqualByComparingTo("126.00");
        assertThat(response.low()).isEqualByComparingTo("119.50");
        assertThat(response.previousClose()).isEqualByComparingTo("121.00");
        assertThat(response.volume()).isEqualTo(987654L);
        assertThat(response.exchangeAt()).isEqualTo(Instant.parse("2026-11-08T09:00:00Z"));
        assertThat(response.source()).isEqualTo("ANGEL_ONE_SMARTAPI_FULL_APP");
        assertThat(response.averagePrice()).isEqualByComparingTo("123.40");
        assertThat(response.week52High()).isEqualByComparingTo("150.00");
        assertThat(response.week52Low()).isEqualByComparingTo("100.10");
        assertThat(response.totalBuyQuantity()).isEqualTo(500L);
        assertThat(response.bids()).hasSize(1);
        assertThat(response.asks().get(0).price()).isEqualByComparingTo("125.60");
        verifyNoInteractions(customerBroker, personalSockets, provider);
    }

    @Test
    void detailQuoteIsUnavailableWithoutApplicationFeedAndNeverFallsBackToBroker() {
        var customer = principal("independent-customer");
        when(catalogue.requireActiveInstrument(42L)).thenReturn(equity());
        when(applicationFeed.available()).thenReturn(false);

        assertThatThrownBy(() -> controller().quoteDetail(customer, 42L))
            .isInstanceOfSatisfying(ResponseStatusException.class,
                ex -> assertThat(ex.getStatusCode().value()).isEqualTo(503));
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
    void closedMarketShowsStoredLastTickAsLastCloseWithChangeOnlyWhenPreviousCloseKnown() {
        var customer = principal("independent-customer");
        var instrument = equity();
        var exchangeAt = Instant.parse("2026-11-06T10:00:00Z");
        var store = mock(MarketLastTickStore.class);
        when(catalogue.requireActiveInstrument(42L)).thenReturn(instrument);
        when(applicationFeed.available()).thenReturn(true);
        when(applicationFeed.snapshot(List.of(instrument))).thenReturn(List.of(
            new CustomerAngelOneLiveQuoteService.LiveQuote(42L, "NSE", "TEST-EQ",
                null, null, null, "MARKET_CLOSED", null)));
        when(store.lastFor(List.of(42L))).thenReturn(Map.of(42L,
            new MarketLastTickStore.LastTick(new BigDecimal("100.00"), new BigDecimal("98.00"), exchangeAt)));
        var controller = new CustomerMarketDataController(identities, catalogue, provider,
            "unrelated-owner-sub", "ANGEL_ONE", customerBroker, personalSockets, applicationFeed, null, store);

        var row = controller.snapshot(customer, List.of(42L)).getBody().rows().get(0);

        assertThat(row.status()).isEqualTo("LAST_CLOSE");
        assertThat(row.ltp()).isEqualByComparingTo("100.00");
        assertThat(row.exchangeAt()).isEqualTo(exchangeAt);
        assertThat(row.source()).isEqualTo("STORED_LAST_LIVE_TICK");
        assertThat(row.changePercent()).isEqualByComparingTo("2.0408");

        when(store.lastFor(List.of(42L))).thenReturn(Map.of(42L,
            new MarketLastTickStore.LastTick(new BigDecimal("100.00"), null, exchangeAt)));
        var firstSession = controller.snapshot(customer, List.of(42L)).getBody().rows().get(0);
        assertThat(firstSession.status()).isEqualTo("LAST_CLOSE");
        assertThat(firstSession.changePercent()).isNull();
        verifyNoInteractions(customerBroker, personalSockets);
    }

    @Test
    void failedApplicationSessionReturnsVerifiedStoredRowsRatherThanFailingThePage() {
        var customer = principal("independent-customer");
        var instrument = equity();
        var store = mock(MarketLastTickStore.class);
        var tickAt = Instant.parse("2026-11-09T09:50:00Z");
        when(catalogue.requireActiveInstrument(42L)).thenReturn(instrument);
        when(applicationFeed.available()).thenReturn(true);
        when(applicationFeed.snapshot(List.of(instrument)))
            .thenThrow(new IllegalStateException("provider session expired"));
        when(store.lastFor(List.of(42L))).thenReturn(Map.of(42L,
            new MarketLastTickStore.LastTick(new BigDecimal("126.20"),
                new BigDecimal("125.00"), tickAt)));
        var controller = new CustomerMarketDataController(identities, catalogue, provider,
            "unrelated-owner-sub", "ANGEL_ONE", customerBroker, personalSockets, applicationFeed, null, store);

        var row = controller.snapshot(customer, List.of(42L)).getBody().rows().get(0);

        assertThat(row.status()).isEqualTo("LAST_CLOSE");
        assertThat(row.ltp()).isEqualByComparingTo("126.20");
        assertThat(row.exchangeAt()).isEqualTo(tickAt);
        assertThat(row.source()).isEqualTo("STORED_LAST_LIVE_TICK");
        verify(store, never()).recordLive(any());
        verifyNoInteractions(customerBroker, personalSockets, provider);
    }

    @Test
    void failedApplicationSessionWithoutSavedDataRemainsUnavailableNotFakeLive() {
        var customer = principal("independent-customer");
        var instrument = equity();
        var store = mock(MarketLastTickStore.class);
        when(catalogue.requireActiveInstrument(42L)).thenReturn(instrument);
        when(applicationFeed.available()).thenReturn(true);
        when(applicationFeed.snapshot(List.of(instrument)))
            .thenThrow(new IllegalStateException("provider session expired"));
        when(store.lastFor(List.of(42L))).thenReturn(Map.of());
        var controller = new CustomerMarketDataController(identities, catalogue, provider,
            "unrelated-owner-sub", "ANGEL_ONE", customerBroker, personalSockets, applicationFeed, null, store);

        var row = controller.snapshot(customer, List.of(42L)).getBody().rows().get(0);

        assertThat(row.status()).isEqualTo("UNAVAILABLE");
        assertThat(row.ltp()).isNull();
        assertThat(row.exchangeAt()).isNull();
        verify(store, never()).recordLive(any());
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

    @Test
    void providerAndBrokerFailuresMapToTypedProblemsNotInternalServerError() {
        var rateLimited = controller().providerFailure(new in.aitradingsystem.provider.angelone
            .AngelOneException(in.aitradingsystem.provider.angelone.AngelOneException.Kind.RATE_LIMITED,
                "RATE_LIMITED", "upstream detail must not leak"));
        assertThat(rateLimited.getStatus()).isEqualTo(429);
        assertThat(rateLimited.getProperties()).containsEntry("code", "PROVIDER_RATE_LIMITED");
        assertThat(rateLimited.getDetail()).doesNotContain("upstream detail");

        var brokerRequired = controller().brokerConnectionRequired();
        assertThat(brokerRequired.getStatus()).isEqualTo(503);
        assertThat(brokerRequired.getProperties()).containsEntry("code", "BROKER_CONNECTION_REQUIRED");
    }
}
