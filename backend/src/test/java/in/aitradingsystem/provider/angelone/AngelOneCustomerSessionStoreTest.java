package in.aitradingsystem.provider.angelone;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import in.aitradingsystem.config.AngelOneProperties;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class AngelOneCustomerSessionStoreTest {
    private static final Instant NOW = Instant.parse("2026-11-02T10:00:00Z");

    @Test
    void connectionIsScopedToExactAppSessionAndRedactsProviderToken() {
        AngelOneCustomerSessionStore store = store();
        UUID sessionA = UUID.randomUUID();
        UUID sessionB = UUID.randomUUID();
        String authToken = "provider-auth-token-secret";

        var connection = store.bind(
                sessionA, "CLIENT1", authToken, OffsetDateTime.ofInstant(NOW.plusSeconds(3600), ZoneOffset.UTC));

        assertThat(store.requireActive(sessionA).clientCode()).isEqualTo("CLIENT1");
        assertThat(connection.toString()).doesNotContain(authToken);
        assertThatThrownBy(() -> store.requireActive(sessionB))
                .isInstanceOfSatisfying(AngelOneException.class,
                        e -> assertThat(e.kind()).isEqualTo(AngelOneException.Kind.SESSION_EXPIRED));
    }

    @Test
    void revokeRemovesOnlyThatAppSessionConnection() {
        AngelOneCustomerSessionStore store = store();
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        OffsetDateTime expiry = OffsetDateTime.ofInstant(NOW.plusSeconds(3600), ZoneOffset.UTC);
        store.bind(first, "CLIENT1", "token-1", expiry);
        store.bind(second, "CLIENT2", "token-2", expiry);

        assertThat(store.revoke(first)).isTrue();
        assertThat(store.findActive(first)).isEmpty();
        assertThat(store.findActive(second)).isPresent();
        assertThat(store.activeConnectionCount()).isEqualTo(1);
    }

    @Test
    void inactiveAppSessionCannotBeBound() {
        AngelOneCustomerSessionStore store = store();
        OffsetDateTime expired = OffsetDateTime.ofInstant(NOW.minusSeconds(1), ZoneOffset.UTC);

        assertThatThrownBy(() -> store.bind(UUID.randomUUID(), "CLIENT1", "token", expired))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("active");
    }

    private static AngelOneCustomerSessionStore store() {
        AngelOneProperties properties = new AngelOneProperties(
                "api-key", "SERVER1", "pin", "JBSWY3DPEHPK3PXP",
                "https://apiconnect.angelone.in", 5000, 10000, 1800, 0, 0);
        return new AngelOneCustomerSessionStore(properties, Clock.fixed(NOW, ZoneOffset.UTC));
    }
}
