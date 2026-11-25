package in.aitradingsystem.intelligence;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class IntelligenceEvidenceContractTest {

    @Test
    void rejectsLookAheadDataCutoff() {
        Instant decision = Instant.parse("2026-11-01T09:30:00Z");
        assertThrows(IllegalArgumentException.class, () -> new IntelligenceEvidenceRequest(
                IntelligenceEvidenceRequest.CONTRACT_VERSION,
                "req-12345678",
                "RELIANCE",
                decision,
                decision.plusSeconds(1),
                "market.v1",
                Map.of(),
                List.of()));
    }

    @Test
    void intelligenceResponseCannotAuthorizeCapital() {
        IntelligenceEvidenceResponse safe = new IntelligenceEvidenceResponse(
                IntelligenceEvidenceRequest.CONTRACT_VERSION,
                "req-12345678",
                true,
                "a".repeat(64),
                List.of(),
                List.of(),
                false,
                Instant.parse("2026-11-01T09:30:00Z"));
        assertFalse(safe.canAuthorizeCapital());

        assertThrows(IllegalArgumentException.class, () -> new IntelligenceEvidenceResponse(
                IntelligenceEvidenceRequest.CONTRACT_VERSION,
                "req-12345678",
                true,
                "b".repeat(64),
                List.of(),
                List.of(),
                true,
                Instant.parse("2026-11-01T09:30:00Z")));
    }
}
