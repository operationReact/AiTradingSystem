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
}
