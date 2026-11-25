package in.aitradingsystem.tradingrooms;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

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
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.server.ResponseStatusException;

class CustomerTradingRoomRenameIT extends AbstractPostgresIT {
    private static final String ISSUER = "https://test-cognito.example";
    @Autowired JdbcClient jdbc;
    @Autowired UserAccountStore users;
    @Autowired CustomerPaperWalletService wallets;

    @Test
    void fundedPaperRoomCanBeRenamedWithoutResettingWalletOrRoomIdentity() {
        UUID owner = customer();
        UUID id = room(owner, "PAPER");
        wallets.activate(owner, id);
        var controller = controller(owner);
        var auth = authentication();
        var before = controller.get(id, auth);

        var updated = controller.rename(id,
            new CustomerTradingRoomController.RenameRoomRequest("  My   Swing   Room  "),
            auth);

        assertThat(updated.id()).isEqualTo(id);
        assertThat(updated.name()).isEqualTo("My Swing Room");
        assertThat(updated.mode()).isEqualTo("PAPER");
        assertThat(updated.status()).isEqualTo("PAPER_FUNDED");
        assertThat(updated.createdAt()).isEqualTo(before.createdAt());
        assertThat(updated.plannedBudgetInr()).isEqualTo(before.plannedBudgetInr());
        assertThat(updated.focus()).isEqualTo(before.focus());
        assertThat(updated.riskPreference()).isEqualTo(before.riskPreference());
        assertThat(wallets.get(owner, id).initialCashPaise()).isEqualTo(2_000_000L);
        assertThat(wallets.get(owner, id).availableCashPaise()).isEqualTo(2_000_000L);
        assertThat(wallets.ledger(owner, id)).hasSize(1);
        assertThat(controller.list(auth))
            .extracting(CustomerTradingRoomController.RoomView::name)
            .contains("My Swing Room");
    }

    @Test
    void distinctEquityFnoAndAllPaperRoomsKeepScopeAcrossCreateListAndRename() {
        UUID owner = customer();
        var controller = controller(owner);
        var auth = authentication();
        var equity = controller.create(new CustomerTradingRoomController.CreateRoomRequest(
            "Cash Portfolio", "SWING", 20000L, "BALANCED", "PAPER", "EQUITY"), auth)
            .getBody();
        var fno = controller.create(new CustomerTradingRoomController.CreateRoomRequest(
            "NIFTY Options Room", "INTRADAY", 20000L, "BALANCED", "PAPER", "FNO"), auth)
            .getBody();
        var all = controller.create(new CustomerTradingRoomController.CreateRoomRequest(
            "All Markets Room", "SWING", 20000L, "BALANCED", "PAPER", "ALL"), auth)
            .getBody();

        assertThat(equity).isNotNull();
        assertThat(fno).isNotNull();
        assertThat(all).isNotNull();
        // Creating a PAPER room now allocates the owner-only virtual wallet
        // exactly once and unpauses it. No F&O quotes or broker orders are
        // fabricated by this purely virtual-cash lifecycle.
        for (var room : List.of(equity, fno, all)) {
            assertThat(room.status()).isEqualTo("PAPER_FUNDED");
            var wallet = wallets.get(owner, room.id());
            assertThat(wallet.initialCashPaise()).isEqualTo(2_000_000L);
            assertThat(wallet.availableCashPaise()).isEqualTo(2_000_000L);
            assertThat(wallet.halted()).isFalse();
            assertThat(wallets.ledger(owner, room.id())).hasSize(1);
            assertThat(wallets.ledger(owner, room.id()).getFirst().eventKind())
                .isEqualTo("INITIAL_PAPER_ALLOCATION");
        }
        assertThat(controller.get(equity.id(), auth).marketScope()).isEqualTo("EQUITY");
        assertThat(controller.get(fno.id(), auth).marketScope()).isEqualTo("FNO");
        assertThat(controller.get(all.id(), auth).marketScope()).isEqualTo("ALL");
        assertThat(controller.list(auth)).extracting(
            CustomerTradingRoomController.RoomView::marketScope)
            .contains("EQUITY", "FNO", "ALL");
        assertThat(jdbc.sql("""
            SELECT market_scope FROM customer_trading_room WHERE id=:id
            """).param("id", fno.id()).query(String.class).single()).isEqualTo("FNO");
        var renamed = controller.rename(fno.id(),
            new CustomerTradingRoomController.RenameRoomRequest("NIFTY Only"),
            auth);
        assertThat(renamed.marketScope()).isEqualTo("FNO");
        assertThat(renamed.id()).isEqualTo(fno.id());
    }

    @Test
    void liveFnoAndAllRoomPlansArePersistedButCannotGrantExecution() {
        UUID owner = customer();
        var controller = controller(owner);
        var auth = authentication();

        for (String scope : List.of("EQUITY", "FNO", "ALL")) {
            var saved = controller.create(
                new CustomerTradingRoomController.CreateRoomRequest(
                    "LIVE PLAN " + scope, "SWING", 20000L, "BALANCED", "LIVE", scope),
                auth).getBody();
            assertThat(saved).isNotNull();
            assertThat(saved.mode()).isEqualTo("LIVE");
            assertThat(saved.marketScope()).isEqualTo(scope);
            assertThat(saved.status()).isEqualTo("DRAFT");
            assertThat(controller.get(saved.id(), auth).marketScope()).isEqualTo(scope);
            assertThat(jdbc.sql("""
                SELECT COUNT(*) FROM customer_paper_account WHERE room_id=:id
                """).param("id", saved.id()).query(Integer.class).single()).isZero();
        }
        assertThatThrownBy(() -> controller.create(
            new CustomerTradingRoomController.CreateRoomRequest(
                "INVALID LIVE", "SWING", 20000L, "BALANCED", "LIVE", "INVALID"), auth))
            .isInstanceOf(ResponseStatusException.class)
            .satisfies(err -> assertThat(((ResponseStatusException) err)
                .getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST));
        var legacy = controller.create(new CustomerTradingRoomController.CreateRoomRequest(
            "Old Client", "SWING", 20000L, "BALANCED", "PAPER"), auth).getBody();
        assertThat(legacy).isNotNull();
        assertThat(legacy.marketScope()).isEqualTo("EQUITY");
        assertThat(legacy.status()).isEqualTo("PAPER_FUNDED");
        assertThat(wallets.get(owner, legacy.id()).halted()).isFalse();
        assertThat(controller.get(legacy.id(), auth).marketScope()).isEqualTo("EQUITY");
    }

    @Test
    void liveRoomRenameKeepsIdModeAndBudget() {
        UUID owner = customer();
        UUID id = room(owner, "LIVE");
        var controller = controller(owner);
        var saved = controller.rename(id,
            new CustomerTradingRoomController.RenameRoomRequest("Momentum Room"),
            authentication());
        assertThat(saved.id()).isEqualTo(id);
        assertThat(saved.name()).isEqualTo("Momentum Room");
        assertThat(saved.mode()).isEqualTo("LIVE");
        assertThat(saved.status()).isEqualTo("DRAFT");
        assertThat(saved.plannedBudgetInr()).isEqualTo(20000);
    }

    @Test
    void ownershipAndCaseInsensitiveUniqueNamesAreEnforced() {
        UUID alice = customer();
        UUID bob = customer();
        UUID one = room(alice, "PAPER");
        UUID two = room(alice, "LIVE");
        var ownController = controller(alice);
        var foreignController = controller(bob);
        var auth = authentication();

        ownController.rename(one,
            new CustomerTradingRoomController.RenameRoomRequest("Swing Desk"),
            auth);
        assertThatThrownBy(() -> ownController.rename(two,
            new CustomerTradingRoomController.RenameRoomRequest("swing desk"),
            auth))
            .isInstanceOf(ResponseStatusException.class)
            .satisfies(err -> assertThat(((ResponseStatusException) err)
                .getStatusCode()).isEqualTo(HttpStatus.CONFLICT));
        assertThatThrownBy(() -> foreignController.rename(one,
            new CustomerTradingRoomController.RenameRoomRequest("Stolen Name"),
            auth))
            .isInstanceOf(ResponseStatusException.class)
            .satisfies(err -> assertThat(((ResponseStatusException) err)
                .getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND));
        assertThat(ownController.get(one, auth).name()).isEqualTo("Swing Desk");
    }

    @Test
    void emptyAndEmbeddedControlCharacterNamesFailWithoutChangingRoom() {
        UUID owner = customer();
        UUID id = room(owner, "PAPER");
        var controller = controller(owner);
        var auth = authentication();
        String old = controller.get(id, auth).name();
        assertThatThrownBy(() -> controller.rename(id,
            new CustomerTradingRoomController.RenameRoomRequest("   "), auth))
            .isInstanceOf(ResponseStatusException.class)
            .satisfies(err -> assertThat(((ResponseStatusException) err)
                .getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST));
        assertThatThrownBy(() -> controller.rename(id,
            new CustomerTradingRoomController.RenameRoomRequest("Hi\u0001There"), auth))
            .isInstanceOf(ResponseStatusException.class);
        assertThat(controller.get(id, auth).name()).isEqualTo(old);
    }

    private CustomerTradingRoomController controller(UUID owner) {
        var identities = mock(CustomerIdentityStore.class);
        when(identities.find("cognito", ISSUER, "test-subject"))
            .thenReturn(Optional.of(new CustomerIdentityStore.CustomerAccount(
                owner, "ACTIVE", "CUSTOMER", "Test User", "test@example.test")));
        return new CustomerTradingRoomController(jdbc, identities, wallets);
    }

    private static Authentication authentication() {
        Jwt jwt = Jwt.withTokenValue("test-jwt")
            .header("alg", "none")
            .claim("iss", ISSUER)
            .subject("test-subject")
            .issuedAt(Instant.now().minusSeconds(60))
            .expiresAt(Instant.now().plusSeconds(300))
            .build();
        return new JwtAuthenticationToken(jwt,
            List.of(new SimpleGrantedAuthority("ROLE_CUSTOMER")));
    }

    private UUID customer() {
        UUID suffix = UUID.randomUUID();
        var user = users.createUser(
            "rename-" + suffix, "Rename test",
            "rename-" + suffix + "@example.test", "test");
        jdbc.sql("UPDATE app_user SET role='CUSTOMER' WHERE id=:id")
            .param("id", user.id()).update();
        return user.id();
    }

    private UUID room(UUID owner, String mode) {
        UUID id = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO customer_trading_room
                    (id, user_id, name, focus, planned_budget_inr, risk_preference, mode)
                VALUES (:id, :owner, :name, 'SWING', 20000, 'BALANCED', :mode)
                """)
            .param("id", id).param("owner", owner)
            .param("name", "Room " + id)
            .param("mode", mode).update();
        return id;
    }
}
