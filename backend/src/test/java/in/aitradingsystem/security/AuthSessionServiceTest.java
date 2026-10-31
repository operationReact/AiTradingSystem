package in.aitradingsystem.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

class AuthSessionServiceTest {

    @Test
    void issuedCredentialIsOpaqueAndRepositoryReceivesOnlyHash() {
        AuthSessionRepository repository = mock(AuthSessionRepository.class);
        UUID userId = UUID.randomUUID();
        AuthSession persisted = new AuthSession(
            UUID.randomUUID(), userId, "subject", "Operator", null,
            OffsetDateTime.now(ZoneOffset.UTC), OffsetDateTime.now(ZoneOffset.UTC).plusHours(8),
            OffsetDateTime.now(ZoneOffset.UTC), null);
        when(repository.insert(any(), any(), anyString(), any(), anyString())).thenReturn(persisted);

        AuthSessionService service = new AuthSessionService(repository);
        AuthSessionService.IssuedSession issued = service.issue(userId, Duration.ofHours(8), "auth-test");

        ArgumentCaptor<String> storedHash = ArgumentCaptor.forClass(String.class);
        Mockito.verify(repository).insert(any(), any(), storedHash.capture(), any(), anyString());

        assertEquals(64, storedHash.getValue().length());
        assertEquals(AuthSessionService.hashToken(issued.token()), storedHash.getValue());
        assertNotEquals(issued.token(), storedHash.getValue());
        assertTrue(issued.token().length() >= 40);
        assertEquals(persisted, issued.session());
    }

    @Test
    void hashIsDeterministicWithoutPersistingRawCredential() {
        String token = "opaque-test-token";
        String digest = AuthSessionService.hashToken(token);
        assertEquals(digest, AuthSessionService.hashToken(token));
        assertEquals(64, digest.length());
        assertNotEquals(token, digest);
    }

    @Test
    void rejectsUnsafeSessionTtl() {
        AuthSessionService service = new AuthSessionService(mock(AuthSessionRepository.class));
        UUID userId = UUID.randomUUID();
        assertThrows(IllegalArgumentException.class,
            () -> service.issue(userId, Duration.ZERO, "auth-test"));
        assertThrows(IllegalArgumentException.class,
            () -> service.issue(userId, Duration.ofDays(31), "auth-test"));
    }
}
