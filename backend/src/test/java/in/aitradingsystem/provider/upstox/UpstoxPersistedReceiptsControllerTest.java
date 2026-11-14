package in.aitradingsystem.provider.upstox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.server.ResponseStatusException;

class UpstoxPersistedReceiptsControllerTest {
    private static final Instant NOW = Instant.parse("2026-11-14T09:00:00Z");

    private static JwtAuthenticationToken jwt(String subject) {
        return new JwtAuthenticationToken(
                Jwt.withTokenValue("fixture").header("alg", "none").claim("sub", subject).build());
    }

    @Test
    void ownerReceivesNoStoreSourceScopedReceiptsWithoutInventedReadiness() {
        var receipts = mock(UpstoxPersistedIngestionReceipts.class);
        var snapshot = new UpstoxPersistedIngestionReceipts.Snapshot(
                "NO_PROVIDER_RECEIPT", null, null, null, null,
                null, null, null, null, null, null, null);
        when(receipts.snapshot(NOW)).thenReturn(snapshot);
        var controller = new UpstoxPersistedReceiptsController(receipts,
                Clock.fixed(NOW, ZoneOffset.UTC), "owner-sub");
        var response = controller.read(jwt("owner-sub"));
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getHeaders().getCacheControl()).contains("no-store");
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().contractVersion())
                .isEqualTo("minttick.upstox.persisted-receipts.v1");
        assertThat(response.getBody().provider()).isEqualTo("UPSTOX");
        assertThat(response.getBody().modelTrainingRightsVerified()).isFalse();
        assertThat(response.getBody().paperTradingEnabled()).isFalse();
        assertThat(response.getBody().receipts().jobs()).isNull();
        verify(receipts).snapshot(NOW);
    }

    @Test
    void missingOwnerOrOtherUserMustNotQueryDatabase() {
        var receipts = mock(UpstoxPersistedIngestionReceipts.class);
        var clock = Clock.fixed(NOW, ZoneOffset.UTC);
        var controller = new UpstoxPersistedReceiptsController(receipts, clock, "owner-sub");
        assertThatThrownBy(() -> controller.read(jwt("other")))
                .isInstanceOf(ResponseStatusException.class).hasMessageContaining("403");
        var unconfigured = new UpstoxPersistedReceiptsController(receipts, clock, "");
        assertThatThrownBy(() -> unconfigured.read(jwt("owner-sub")))
                .isInstanceOf(ResponseStatusException.class).hasMessageContaining("403");
        verifyNoInteractions(receipts);
    }
}
