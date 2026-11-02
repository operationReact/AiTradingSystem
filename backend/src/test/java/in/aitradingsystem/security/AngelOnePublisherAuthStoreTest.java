package in.aitradingsystem.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

class AngelOnePublisherAuthStoreTest {
    private static final String VERIFIER = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789-._~abc";

    @Test
    void exchangeIsPkceBoundOneTimeAndRedactsProviderToken() {
        AngelOnePublisherAuthStore store = new AngelOnePublisherAuthStore(
                Clock.fixed(Instant.parse("2026-11-02T10:00:00Z"), ZoneOffset.UTC));
        String challenge = AngelOnePublisherAuthStore.pkceChallenge(VERIFIER);
        var start = store.begin(challenge);
        var pending = store.consumeAuthorization(start.state());
        String authToken = "provider-auth-token-secret";
        var exchange = store.createExchange(pending, "CLIENT1", authToken, "Satya", "s@example.test");

        assertThatThrownBy(() -> store.consumeExchange(exchange.code(), VERIFIER + "x"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("verifier");

        var consumed = store.consumeExchange(exchange.code(), VERIFIER);
        assertThat(consumed.clientCode()).isEqualTo("CLIENT1");
        assertThat(consumed.toString()).doesNotContain(authToken);
        assertThatThrownBy(() -> store.consumeExchange(exchange.code(), VERIFIER))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("missing or expired");
    }

    @Test
    void authorizationStateIsOneTime() {
        AngelOnePublisherAuthStore store = new AngelOnePublisherAuthStore(
                Clock.fixed(Instant.parse("2026-11-02T10:00:00Z"), ZoneOffset.UTC));
        var start = store.begin(AngelOnePublisherAuthStore.pkceChallenge(VERIFIER));

        store.consumeAuthorization(start.state());

        assertThatThrownBy(() -> store.consumeAuthorization(start.state()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("missing or expired");
    }

    @Test
    void invalidChallengeIsRejectedBeforeStateIsCreated() {
        AngelOnePublisherAuthStore store = new AngelOnePublisherAuthStore(
                Clock.fixed(Instant.parse("2026-11-02T10:00:00Z"), ZoneOffset.UTC));

        assertThatThrownBy(() -> store.begin("not-a-valid-pkce-challenge"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("codeChallenge");
    }
}
