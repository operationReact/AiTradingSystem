package in.aitradingsystem.research;

import java.net.URI;
import java.time.Instant;
import java.util.Objects;

/**
 * Provenance-carrying research evidence. Research/AI evidence is deliberately non-authoritative:
 * callers must never use this type as execution approval or as a replacement for deterministic
 * risk checks.
 */
public record ResearchEvidence(
        String provider,
        String model,
        URI sourceUri,
        Instant sourcePublishedAt,
        Instant retrievedAt,
        String evidenceText,
        InferenceStatus inferenceStatus) {

    public enum InferenceStatus {
        VERIFIED_SOURCE,
        BOUNDED_INFERENCE,
        UNUSABLE
    }

    public ResearchEvidence {
        provider = requireText(provider, "provider");
        model = requireText(model, "model");
        sourceUri = Objects.requireNonNull(sourceUri, "sourceUri");
        if (!sourceUri.isAbsolute() || sourceUri.getScheme() == null
                || !(sourceUri.getScheme().equalsIgnoreCase("https") || sourceUri.getScheme().equalsIgnoreCase("http"))) {
            throw new IllegalArgumentException("sourceUri must be an absolute HTTP(S) URI");
        }
        sourcePublishedAt = Objects.requireNonNull(sourcePublishedAt, "sourcePublishedAt");
        retrievedAt = Objects.requireNonNull(retrievedAt, "retrievedAt");
        if (sourcePublishedAt.isAfter(retrievedAt)) {
            throw new IllegalArgumentException("sourcePublishedAt cannot be after retrievedAt");
        }
        evidenceText = requireText(evidenceText, "evidenceText");
        inferenceStatus = Objects.requireNonNull(inferenceStatus, "inferenceStatus");
    }

    /**
     * Research evidence can be displayed/compared only when provenance is usable. This is not, and
     * intentionally cannot become, trade authorization.
     */
    public boolean usableForResearch() {
        return inferenceStatus != InferenceStatus.UNUSABLE;
    }

    /** Always false by design: deterministic risk/execution policy is the only authorization path. */
    public boolean authorizesTrade() {
        return false;
    }

    private static String requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must be provided");
        }
        return value.trim();
    }
}