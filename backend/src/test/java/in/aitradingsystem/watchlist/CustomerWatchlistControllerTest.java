package in.aitradingsystem.watchlist;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import in.aitradingsystem.instrument.Instrument;
import in.aitradingsystem.instrument.InstrumentMasterException;
import in.aitradingsystem.instrument.InstrumentMasterService;
import in.aitradingsystem.user.CustomerIdentityStore;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
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

class CustomerWatchlistControllerTest {
    private static final String ISSUER =
        "https://cognito-idp.ap-south-1.amazonaws.com/ap-south-1_test";
    private final CustomerIdentityStore identities = mock(CustomerIdentityStore.class);
    private final CustomerWatchlistStore store = mock(CustomerWatchlistStore.class);
    private final InstrumentMasterService instruments = mock(InstrumentMasterService.class);

    private JwtAuthenticationToken principal(String subject, UUID userId, String status, String role) {
        var jwt = Jwt.withTokenValue("signed-test-access")
            .header("alg", "RS256").issuer(ISSUER).subject(subject)
            .issuedAt(Instant.parse("2026-11-07T00:00:00Z"))
            .expiresAt(Instant.parse("2026-11-08T00:00:00Z")).build();
        when(identities.find("cognito", ISSUER, subject))
            .thenReturn(Optional.of(new CustomerIdentityStore.CustomerAccount(
                userId, status, role, "Test", "customer@example.invalid")));
        return new JwtAuthenticationToken(jwt, List.of(new SimpleGrantedAuthority("ROLE_CUSTOMER")));
    }

    private CustomerWatchlistController controller() {
        return new CustomerWatchlistController(identities, store, instruments);
    }

    private static Instrument instrument(long id) {
        return new Instrument(id, "NSE_EQ", "NSE", "2885", "RELIANCE", "Reliance Industries",
            "EQ", (LocalDate) null, (BigDecimal) null, 1, new BigDecimal("0.05"), UUID.randomUUID());
    }

    @Test
    void addsInstrumentForOwnerAndReturnsCreated() {
        UUID owner = UUID.randomUUID();
        var customer = principal("customer-a", owner, "ACTIVE", "CUSTOMER");
        when(instruments.requireActiveInstrument(42L)).thenReturn(instrument(42L));
        when(store.add(eq(owner), any())).thenReturn(CustomerWatchlistStore.AddOutcome.ADDED);

        var response = controller().add(customer, new CustomerWatchlistController.AddRequest(42L));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(response.getBody().tradingSymbol()).isEqualTo("RELIANCE");
        assertThat(response.getBody().exchange()).isEqualTo("NSE");
        verify(store).add(eq(owner), any());
    }

    @Test
    void reAddingExistingItemIsIdempotentOk() {
        UUID owner = UUID.randomUUID();
        var customer = principal("customer-a", owner, "ACTIVE", "CUSTOMER");
        when(instruments.requireActiveInstrument(42L)).thenReturn(instrument(42L));
        when(store.add(eq(owner), any())).thenReturn(CustomerWatchlistStore.AddOutcome.ALREADY_PRESENT);

        var response = controller().add(customer, new CustomerWatchlistController.AddRequest(42L));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void limitReachedIsRejectedWithProblemCode() {
        UUID owner = UUID.randomUUID();
        var customer = principal("customer-a", owner, "ACTIVE", "CUSTOMER");
        when(instruments.requireActiveInstrument(42L)).thenReturn(instrument(42L));
        when(store.add(eq(owner), any())).thenReturn(CustomerWatchlistStore.AddOutcome.LIMIT_REACHED);

        assertThatThrownBy(() -> controller().add(customer,
                new CustomerWatchlistController.AddRequest(42L)))
            .isInstanceOf(CustomerWatchlistController.WatchlistLimitReachedException.class);
    }

    @Test
    void unknownInstrumentNeverWritesToStore() {
        var customer = principal("customer-a", UUID.randomUUID(), "ACTIVE", "CUSTOMER");
        when(instruments.requireActiveInstrument(999L))
            .thenThrow(new InstrumentMasterException("INSTRUMENT_NOT_FOUND", "missing"));

        assertThatThrownBy(() -> controller().add(customer,
                new CustomerWatchlistController.AddRequest(999L)))
            .isInstanceOf(InstrumentMasterException.class);
        verify(store, never()).add(any(), any());
    }

    @Test
    void listAndRemoveAreScopedToOwner() {
        UUID owner = UUID.randomUUID();
        var customer = principal("customer-a", owner, "ACTIVE", "CUSTOMER");
        when(store.list(owner)).thenReturn(List.of(new CustomerWatchlistStore.Item(42L, "NSE",
            "RELIANCE", Instant.parse("2026-11-06T10:00:00Z"))));
        when(instruments.activeInstrumentsByIds(List.of(42L))).thenReturn(Map.of(42L, instrument(42L)));

        var list = controller().list(customer).getBody();
        controller().remove(customer, 42L);

        assertThat(list.items()).hasSize(1);
        assertThat(list.items().get(0).status()).isEqualTo("ACTIVE");
        assertThat(list.maxItems()).isEqualTo(CustomerWatchlistStore.MAX_ITEMS);
        verify(store).remove(owner, 42L);
        verify(store, never()).repoint(any(), anyLong(), anyLong());
    }

    @Test
    void staleIdIsRepointedToCurrentInstrumentWithSameSymbol() {
        UUID owner = UUID.randomUUID();
        var customer = principal("customer-a", owner, "ACTIVE", "CUSTOMER");
        when(store.list(owner)).thenReturn(List.of(new CustomerWatchlistStore.Item(7L, "NSE",
            "RELIANCE", Instant.parse("2026-11-06T10:00:00Z"))));
        when(instruments.activeInstrumentBySymbol("NSE", "RELIANCE")).thenReturn(Optional.of(instrument(42L)));

        var list = controller().list(customer).getBody();

        assertThat(list.items()).hasSize(1);
        assertThat(list.items().get(0).instrumentId()).isEqualTo(42L);
        assertThat(list.items().get(0).status()).isEqualTo("ACTIVE");
        verify(store).repoint(owner, 7L, 42L);
    }

    @Test
    void rowWithNoCurrentInstrumentIsReturnedUnavailableAndNotRewritten() {
        UUID owner = UUID.randomUUID();
        var customer = principal("customer-a", owner, "ACTIVE", "CUSTOMER");
        when(store.list(owner)).thenReturn(List.of(new CustomerWatchlistStore.Item(7L, "NSE",
            "DELISTEDCO", Instant.parse("2026-11-06T10:00:00Z"))));

        var list = controller().list(customer).getBody();

        assertThat(list.items().get(0).instrumentId()).isEqualTo(7L);
        assertThat(list.items().get(0).status()).isEqualTo("UNAVAILABLE");
        verify(store, never()).repoint(any(), anyLong(), anyLong());
    }

    @Test
    void suspendedOrNonCustomerAccountsAreUnauthorizedBeforeStoreAccess() {
        var suspended = principal("customer-a", UUID.randomUUID(), "SUSPENDED", "CUSTOMER");

        assertThatThrownBy(() -> controller().list(suspended))
            .isInstanceOf(ResponseStatusException.class)
            .satisfies(ex -> assertThat(((ResponseStatusException) ex).getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED));
        verifyNoInteractions(store);
    }

    @Test
    void anonymousCallersAreRejected() {
        assertThatThrownBy(() -> controller().list(null))
            .isInstanceOf(ResponseStatusException.class);
        verifyNoInteractions(store);
    }
}
