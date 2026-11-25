package in.aitradingsystem.provider.upstox;

import static org.junit.jupiter.api.Assertions.*;
import java.time.Duration;
import org.junit.jupiter.api.Test;

class UpstoxSourceFailureClassifierTest {
    @Test void bounded429CooldownRequiresExplicitEvidence() {
        var failure = UpstoxSourceFailureClassifier.http(429, 60L);
        assertEquals(UpstoxSourceFailureClassifier.Code.RATE_LIMITED, failure.code());
        assertEquals(Duration.ofSeconds(60), failure.cooldown().orElseThrow());
        assertFalse(failure.retryCandidate());
        assertEquals(Duration.ZERO, UpstoxSourceFailureClassifier.http(429, 0L).cooldown().orElseThrow());
        assertEquals(Duration.ofDays(1), UpstoxSourceFailureClassifier.http(429, 86400L).cooldown().orElseThrow());
        assertTrue(UpstoxSourceFailureClassifier.http(429, -1L).cooldown().isEmpty());
        assertTrue(UpstoxSourceFailureClassifier.http(429, 86401L).cooldown().isEmpty());
        assertTrue(UpstoxSourceFailureClassifier.http(429, null).cooldown().isEmpty());
    }

    @Test void authFailuresAreNotRetryCandidates() {
        assertEquals(UpstoxSourceFailureClassifier.Code.UNAUTHORIZED,
                UpstoxSourceFailureClassifier.http(401, null).code());
        assertFalse(UpstoxSourceFailureClassifier.http(403, null).retryCandidate());
        assertFalse(UpstoxSourceFailureClassifier.http(404, null).retryCandidate());
    }

    @Test void upstreamAndTransportFailuresAreCandidatesNotAutomaticRetries() {
        assertTrue(UpstoxSourceFailureClassifier.http(503, null).retryCandidate());
        assertTrue(UpstoxSourceFailureClassifier.transport().retryCandidate());
        assertFalse(UpstoxSourceFailureClassifier.http(422, null).retryCandidate());
        assertThrows(IllegalArgumentException.class,
                () -> UpstoxSourceFailureClassifier.http(200, null));
    }

    @Test void only429MayCarryCooldownAndNoAutomaticRetry() {
        for (int status : new int[]{400, 401, 403, 404, 500, 503, 599}) {
            assertTrue(UpstoxSourceFailureClassifier.http(status, 60L).cooldown().isEmpty());
        }
        assertFalse(UpstoxSourceFailureClassifier.http(429, 60L).retryCandidate());
        assertEquals(UpstoxSourceFailureClassifier.Code.TRANSPORT_FAILURE,
                UpstoxSourceFailureClassifier.transport().code());
    }
}
