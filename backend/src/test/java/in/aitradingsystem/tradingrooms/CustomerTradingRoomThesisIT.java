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

class CustomerTradingRoomThesisIT extends AbstractPostgresIT {
    private static final String ISSUER = "https://test-cognito.example";
    @Autowired JdbcClient jdbc;
    @Autowired UserAccountStore users;

    @Test
    void roomThesisPersistsWithOptimisticRevisionAndCanBeCleared() {
        UUID owner = customer();
        UUID room = room(owner, "PAPER");
        var controller = controller(owner);
        var auth = authentication();

        var empty = controller.get(room, auth);
        assertThat(empty.roomId()).isEqualTo(room);
        assertThat(empty.content()).isEmpty();
        assertThat(empty.revision()).isZero();
        assertThat(empty.updatedAt()).isNull();

        var first = controller.save(room,
            new CustomerTradingRoomThesisController.SaveThesisRequest(
                "Buy only after a clean retest.\r\nInvalidate below prior support.", 0), auth);
        assertThat(first.revision()).isEqualTo(1);
        assertThat(first.content()).isEqualTo(
            "Buy only after a clean retest.\nInvalidate below prior support.");
        assertThat(first.updatedAt()).isNotNull();

        var second = controller.save(room,
            new CustomerTradingRoomThesisController.SaveThesisRequest(
                "Updated plan", first.revision()), auth);
        assertThat(second.revision()).isEqualTo(2);
        assertThat(second.content()).isEqualTo("Updated plan");

        assertThatThrownBy(() -> controller.save(room,
            new CustomerTradingRoomThesisController.SaveThesisRequest(
                "Stale editor", first.revision()), auth))
            .isInstanceOf(ResponseStatusException.class)
            .satisfies(error -> assertThat(((ResponseStatusException) error)
                .getStatusCode()).isEqualTo(HttpStatus.CONFLICT));
        assertThat(controller.get(room, auth).content()).isEqualTo("Updated plan");

        var cleared = controller.save(room,
            new CustomerTradingRoomThesisController.SaveThesisRequest("", second.revision()), auth);
        assertThat(cleared.revision()).isEqualTo(3);
        assertThat(cleared.content()).isEmpty();
    }

    @Test
    void foreignRoomIsNotReadableOrWritableAndControlCharactersAreRejected() {
        UUID owner = customer();
        UUID other = customer();
        UUID room = room(owner, "LIVE");
        var ownController = controller(owner);
        var otherController = controller(other);
        var auth = authentication();

        assertThatThrownBy(() -> otherController.get(room, auth))
            .isInstanceOf(ResponseStatusException.class)
            .satisfies(error -> assertThat(((ResponseStatusException) error)
                .getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND));
        assertThatThrownBy(() -> otherController.save(room,
            new CustomerTradingRoomThesisController.SaveThesisRequest("stolen", 0), auth))
            .isInstanceOf(ResponseStatusException.class)
            .satisfies(error -> assertThat(((ResponseStatusException) error)
                .getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND));

        assertThatThrownBy(() -> ownController.save(room,
            new CustomerTradingRoomThesisController.SaveThesisRequest("bad\u0001note", 0), auth))
            .isInstanceOf(ResponseStatusException.class)
            .satisfies(error -> assertThat(((ResponseStatusException) error)
                .getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST));
        assertThatThrownBy(() -> ownController.save(room,
            new CustomerTradingRoomThesisController.SaveThesisRequest("x".repeat(5001), 0), auth))
            .isInstanceOf(ResponseStatusException.class)
            .satisfies(error -> assertThat(((ResponseStatusException) error)
                .getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST));
    }

    private CustomerTradingRoomThesisController controller(UUID owner) {
        var identities = mock(CustomerIdentityStore.class);
        when(identities.find("cognito", ISSUER, "test-subject"))
            .thenReturn(Optional.of(new CustomerIdentityStore.CustomerAccount(
                owner, "ACTIVE", "CUSTOMER", "Test User", "test@example.test")));
        return new CustomerTradingRoomThesisController(jdbc, identities);
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
            "thesis-" + suffix, "Thesis test",
            "thesis-" + suffix + "@example.test", "test");
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
            .param("name", "Thesis room " + id)
            .param("mode", mode).update();
        return id;
    }
}
