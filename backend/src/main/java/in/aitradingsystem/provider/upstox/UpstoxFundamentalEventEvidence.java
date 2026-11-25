package in.aitradingsystem.provider.upstox;

import java.time.Instant;
import java.util.Objects;

/**
 * Source-only point-in-time evidence contract. Not an Upstox endpoint adapter,
 * a persisted dataset, or permission to train/publish. Missing rights and
 * unavailable publication timestamps must fail closed.
 */
public final class UpstoxFundamentalEventEvidence {
    private UpstoxFundamentalEventEvidence() {}

    public enum Kind { CURRENT_KEY_RATIO, ISSUER_FILING, CORPORATE_ACTION }
    public enum Rights { UNVERIFIED, READ_ONLY, RESEARCH_RETENTION_VERIFIED }
    public enum Decision { ELIGIBLE, RIGHTS_BLOCKED, TIME_BLOCKED, SOURCE_BLOCKED }

    public record Evidence(
            Kind kind, String provider, String isin, String sourceReference,
            Instant publishedAt, Instant availableAt, Instant acquiredAt,
            Rights rights, boolean sourceVerified) {
        public Evidence {
            Objects.requireNonNull(kind, "kind");
            Objects.requireNonNull(rights, "rights");
            if (provider == null || provider.isBlank() || sourceReference == null
                    || sourceReference.isBlank() || isin == null
                    || !isin.matches("[A-Z]{2}[A-Z0-9]{10}")) {
                throw new IllegalArgumentException("Invalid source identity");
            }
        }
    }

    /** This is a producer-side eligibility check, never execution authorization. */
    public static Decision researchAt(Evidence evidence, Instant decisionAt) {
        Objects.requireNonNull(evidence, "evidence");
        Objects.requireNonNull(decisionAt, "decisionAt");
        if (!evidence.sourceVerified()) return Decision.SOURCE_BLOCKED;
        if (evidence.rights() != Rights.RESEARCH_RETENTION_VERIFIED) return Decision.RIGHTS_BLOCKED;
        if (evidence.publishedAt() == null || evidence.availableAt() == null
                || evidence.acquiredAt() == null
                || evidence.publishedAt().isAfter(evidence.availableAt())
                || evidence.availableAt().isAfter(evidence.acquiredAt())
                || evidence.publishedAt().isAfter(decisionAt)
                || evidence.availableAt().isAfter(decisionAt)
                || evidence.acquiredAt().isAfter(decisionAt)) {
            return Decision.TIME_BLOCKED;
        }
        return Decision.ELIGIBLE;
    }
}
