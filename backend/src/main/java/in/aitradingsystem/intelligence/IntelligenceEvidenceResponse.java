package in.aitradingsystem.intelligence;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.Instant;
import java.util.List;

/** Evidence response only. This type intentionally contains no execution authorization. */
public record IntelligenceEvidenceResponse(
        @JsonProperty("contract_version") String contractVersion,
        @JsonProperty("request_id") String requestId,
        boolean accepted,
        @JsonProperty("input_fingerprint") String inputFingerprint,
        List<EvidenceItem> evidence,
        List<String> warnings,
        @JsonProperty("can_authorize_capital") boolean canAuthorizeCapital,
        @JsonProperty("generated_at") Instant generatedAt) {

    public IntelligenceEvidenceResponse {
        evidence = evidence == null ? List.of() : List.copyOf(evidence);
        warnings = warnings == null ? List.of() : List.copyOf(warnings);
        if (canAuthorizeCapital) {
            throw new IllegalArgumentException("Intelligence service must never authorize capital");
        }
    }

    public record EvidenceItem(String family, String provider, String version,
                               double score, double confidence,
                               @JsonProperty("observed_at") Instant observedAt,
                               String explanation) {}
}
