package in.aitradingsystem.research;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class ResearchEvidenceTest {

    private static final Instant PUBLISHED = Instant.parse("2026-10-28T09:00:00Z");
    private static final Instant RETRIEVED = Instant.parse("2026-10-28T09:05:00Z");

    @Test
    void boundedInferenceIsResearchUsableButNeverTradeAuthorization() {
        ResearchEvidence evidence = evidence(ResearchEvidence.InferenceStatus.BOUNDED_INFERENCE);

        assertTrue(evidence.usableForResearch());
        assertFalse(evidence.authorizesTrade());
    }

    @Test
    void unusableInferenceFailsClosedForResearchAndTradeAuthorization() {
        ResearchEvidence evidence = evidence(ResearchEvidence.InferenceStatus.UNUSABLE);

        assertFalse(evidence.usableForResearch());
        assertFalse(evidence.authorizesTrade());
    }

    @Test
    void rejectsMissingOrInvalidProvenance() {
        assertThrows(IllegalArgumentException.class, () -> new ResearchEvidence(
                "Gemini", "model", URI.create("relative/path"), PUBLISHED, RETRIEVED,
                "evidence", ResearchEvidence.InferenceStatus.VERIFIED_SOURCE));
        assertThrows(IllegalArgumentException.class, () -> new ResearchEvidence(
                "Gemini", "model", URI.create("https://example.test/source"), RETRIEVED, PUBLISHED,
                "evidence", ResearchEvidence.InferenceStatus.VERIFIED_SOURCE));
        assertThrows(IllegalArgumentException.class, () -> new ResearchEvidence(
                "Gemini", " ", URI.create("https://example.test/source"), PUBLISHED, RETRIEVED,
                "evidence", ResearchEvidence.InferenceStatus.VERIFIED_SOURCE));
    }

    private static ResearchEvidence evidence(ResearchEvidence.InferenceStatus status) {
        return new ResearchEvidence(
                "Gemini",
                "gemini-bounded",
                URI.create("https://example.test/source"),
                PUBLISHED,
                RETRIEVED,
                "Source-backed research observation",
                status);
    }
}