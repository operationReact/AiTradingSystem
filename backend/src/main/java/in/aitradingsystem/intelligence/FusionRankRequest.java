package in.aitradingsystem.intelligence;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * Evidence-only request to the Python signal-fusion plane.
 * The request has no order, quantity, account, approval, or execution fields by design.
 */
public record FusionRankRequest(
        @JsonProperty("fusion_version") String fusionVersion,
        String symbol,
        @JsonProperty("as_of") Instant asOf,
        @JsonProperty("max_age_seconds") int maxAgeSeconds,
        FusionEvidence deterministic,
        FusionEvidence quant,
        FusionEvidence gemini) {

    public static final String VERSION = "signal-fusion.v1";

    public FusionRankRequest {
        if (!VERSION.equals(fusionVersion)) {
            throw new IllegalArgumentException("Unsupported fusion contract version");
        }
        Objects.requireNonNull(symbol, "symbol");
        Objects.requireNonNull(asOf, "asOf");
        Objects.requireNonNull(deterministic, "deterministic");
        Objects.requireNonNull(quant, "quant");
        if (symbol.isBlank() || symbol.length() > 32) {
            throw new IllegalArgumentException("symbol must contain 1..32 characters");
        }
        if (maxAgeSeconds < 1 || maxAgeSeconds > 86_400) {
            throw new IllegalArgumentException("maxAgeSeconds must be within 1..86400");
        }
        if (!"DETERMINISTIC".equals(deterministic.family())) {
            throw new IllegalArgumentException("deterministic evidence family must be DETERMINISTIC");
        }
        if (!"QUANT".equals(quant.family())) {
            throw new IllegalArgumentException("quant evidence family must be QUANT");
        }
        if (gemini != null && !"GEMINI".equals(gemini.family())) {
            throw new IllegalArgumentException("gemini evidence family must be GEMINI");
        }
        for (FusionEvidence evidence : List.of(deterministic, quant)) {
            if (evidence.observedAt().isAfter(asOf)) {
                throw new IllegalArgumentException("required evidence cannot be newer than asOf");
            }
        }
        if (gemini != null && gemini.observedAt().isAfter(asOf)) {
            throw new IllegalArgumentException("Gemini evidence cannot be newer than asOf");
        }
    }

    public record FusionEvidence(
            String family,
            String provider,
            String version,
            double score,
            double confidence,
            @JsonProperty("observed_at") Instant observedAt,
            String fingerprint,
            List<String> reasons) {

        public FusionEvidence {
            Objects.requireNonNull(family, "family");
            Objects.requireNonNull(provider, "provider");
            Objects.requireNonNull(version, "version");
            Objects.requireNonNull(observedAt, "observedAt");
            Objects.requireNonNull(fingerprint, "fingerprint");
            if (!Double.isFinite(score) || score < -1.0 || score > 1.0) {
                throw new IllegalArgumentException("score must be finite and within [-1, 1]");
            }
            if (!Double.isFinite(confidence) || confidence < 0.0 || confidence > 1.0) {
                throw new IllegalArgumentException("confidence must be finite and within [0, 1]");
            }
            if (!fingerprint.matches("[a-f0-9]{64}")) {
                throw new IllegalArgumentException("fingerprint must be a lowercase SHA-256 hex value");
            }
            reasons = reasons == null ? List.of() : List.copyOf(reasons);
        }
    }
}
