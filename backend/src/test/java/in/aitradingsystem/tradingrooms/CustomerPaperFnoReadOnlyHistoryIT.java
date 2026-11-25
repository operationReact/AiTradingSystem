package in.aitradingsystem.tradingrooms;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

import in.aitradingsystem.support.AbstractPostgresIT;
import in.aitradingsystem.user.CustomerIdentityStore;
import in.aitradingsystem.user.UserAccountStore;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.server.ResponseStatusException;

/** Database-backed owner isolation, without ANY broker or market-source bean. */
class CustomerPaperFnoReadOnlyHistoryIT extends AbstractPostgresIT {
    private static final String ISSUER = "https://test-cognito.example";
    @Autowired JdbcClient jdbc;
    @Autowired UserAccountStore users;
    @Autowired CustomerPaperWalletService wallets;

    @Test
    void fundedFnoHistoryAndReconciliationRemainAvailableWithFeedDisabled() {
        UUID owner = customer();
        UUID id = room(owner, "FNO", "PAPER");
        wallets.activate(owner, id);
        var before = wallets.get(owner, id);
        var identities = identities(owner);
        var controller = new CustomerPaperFnoHistoryController(jdbc, identities);

        assertThat(controller.orders(id, auth()).getBody()).isEmpty();
        assertThat(controller.positions(id, auth()).getBody()).isEmpty();
        assertThat(controller.orders(id, auth()).getHeaders().getCacheControl())
            .contains("no-store");
        var audit = new CustomerPaperFnoReconciliationController(
            new CustomerPaperFnoReconciliationService(jdbc),
            new CustomerPaperSharedWalletAuditService(jdbc), identities, jdbc);
        var receipt = audit.receipt(id, auth()).getBody();
        assertThat(receipt.status()).isEqualTo("NO_FILLS");
        assertThat(receipt.tradingApproved()).isFalse();
        assertThat(receipt.marketDataVerified()).isFalse();
        assertThat(audit.walletReconciliation(id, auth()).getBody()
            .tradingApproved()).isFalse();

        var after = wallets.get(owner, id);
        assertThat(after.availableCashPaise()).isEqualTo(before.availableCashPaise());
        assertThat(wallets.ledger(owner, id)).hasSize(1);
    }

    @Test
    void readOnlyEndpointRejectsOtherOwnerAndEquityOrLiveRoom() {
        UUID owner = customer(), another = customer();
        UUID fno = room(owner, "FNO", "PAPER");
        UUID equity = room(owner, "EQUITY", "PAPER");
        UUID live = room(owner, "FNO", "LIVE");
        wallets.activate(owner, fno);
        wallets.activate(owner, equity);

        var foreign = new CustomerPaperFnoHistoryController(jdbc, identities(another));
        var own = new CustomerPaperFnoHistoryController(jdbc, identities(owner));
        for (var candidate : List.of(fno)) {
            assertNotFound(() -> foreign.orders(candidate, auth()));
            assertNotFound(() -> foreign.positions(candidate, auth()));
        }
        assertNotFound(() -> own.orders(equity, auth()));
        assertNotFound(() -> own.positions(equity, auth()));
        assertNotFound(() -> own.orders(live, auth()));
        assertNotFound(() -> own.positions(live, auth()));
    }

    @Test
    void unauthenticatedRequestIsRejectedBeforeDatabaseAccess() {
        var database = mock(JdbcClient.class);
        var identities = mock(CustomerIdentityStore.class);
        var controller = new CustomerPaperFnoHistoryController(database, identities);
        assertThatThrownBy(() -> controller.orders(UUID.randomUUID(), null))
            .isInstanceOf(ResponseStatusException.class)
            .satisfies(ex -> assertThat(((ResponseStatusException) ex).getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED));
        verifyNoInteractions(database, identities);
    }

    private static void assertNotFound(Runnable run) {
        assertThatThrownBy(run::run)
            .isInstanceOf(ResponseStatusException.class)
            .satisfies(ex -> assertThat(((ResponseStatusException) ex).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND));
    }

    private CustomerIdentityStore identities(UUID owner) {
        var store = mock(CustomerIdentityStore.class);
        when(store.find("cognito", ISSUER, "test-subject"))
            .thenReturn(Optional.of(new CustomerIdentityStore.CustomerAccount(
                owner, "ACTIVE", "CUSTOMER", "User", "user@example.test")));
        return store;
    }

    private static JwtAuthenticationToken auth() {
        var jwt = Jwt.withTokenValue("test")
            .header("alg", "none").claim("iss", ISSUER)
            .subject("test-subject")
            .issuedAt(Instant.now().minusSeconds(10))
            .expiresAt(Instant.now().plusSeconds(60)).build();
        return new JwtAuthenticationToken(jwt,
            List.of(new SimpleGrantedAuthority("ROLE_CUSTOMER")));
    }

    private UUID customer() {
        UUID suffix = UUID.randomUUID();
        var user = users.createUser("read-only-fno-" + suffix,
            "FNO history", "fno-read-only-" + suffix + "@example.test", "test");
        jdbc.sql("UPDATE app_user SET role='CUSTOMER' WHERE id=:id")
            .param("id", user.id()).update();
        return user.id();
    }

    private UUID room(UUID owner, String scope, String mode) {
        UUID id = UUID.randomUUID();
        jdbc.sql("""
            INSERT INTO customer_trading_room
            (id,user_id,name,focus,planned_budget_inr,risk_preference,mode,
             market_scope,status)
            VALUES (:id,:owner,:name,'INTRADAY',20000,'BALANCED',:mode,:scope,'DRAFT')
            """).param("id", id).param("owner", owner)
            .param("name", "FNO read-only " + id)
            .param("mode", mode).param("scope", scope).update();
        return id;
    }
}
