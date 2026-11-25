package in.aitradingsystem.intelligence;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * Ranked intelligence candidate only. The record rejects any attempt by the intelligence plane to
 * authorize capital or return an executable status.
 */
public record FusionRankResponse(
        @JsonProperty("fusion_version") String fusionVersion,
        String symbol,
        @JsonProperty("as_of") Instant asOf,
        @JsonProperty("data_cutoff") Instant dataCutoff,
        String action,
        @JsonProperty("fused_score") double fusedScore,
        @JsonProperty("rank_score") double rankScore,
        double confidence,
        List<Contribution> contributions,
        @JsonProperty("reason_codes") List<String> reasonCodes,
        List<String> provenance,
        @JsonProperty("opportunity_fingerprint") String opportunityFingerprint,
        String status,
        @JsonProperty("can_authorize_capital") boolean canAuthorizeCapital) {

    public FusionRankResponse {
        if (!FusionRankRequest.VERSION.equals(fusionVersion)) {
            throw new IllegalArgumentException("Unsupported fusion response version");
        }
        Objects.requireNonNull(symbol, "symbol");
        Objects.requireNonNull(asOf, "asOf");
        Objects.requireNonNull(dataCutoff, "dataCutoff");
        Objects.requireNonNull(action, "action");
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(opportunityFingerprint, "opportunityFingerprint");
        if (canAuthorizeCapital) {
            throw new IllegalArgumentException("Intelligence service must never authorize capital");
        }
        if (!"CANDIDATE_NOT_EXECUTABLE".equals(status)) {
            throw new IllegalArgumentException("Fusion response must remain non-executable");
        }
        if (!List.of("BUY", "WATCH").contains(action)) {
            throw new IllegalArgumentException("Unsupported fusion action");
        }
        if (dataCutoff.isAfter(asOf)) {
            throw new IllegalArgumentException("dataCutoff cannot be after asOf");
        }
        if (!Double.isFinite(fusedScore) || fusedScore < -1.0 || fusedScore > 1.0) {
            throw new IllegalArgumentException("fusedScore must be within [-1, 1]");
        }
        if (!Double.isFinite(rankScore) || rankScore < 0.0 || rankScore > 1.0) {
            throw new IllegalArgumentException("rankScore must be within [0, 1]");
        }
        if (!Double.isFinite(confidence) || confidence < 0.0 || confidence > 1.0) {
            throw new IllegalArgumentException("confidence must be within [0, 1]");
        }
        if (!opportunityFingerprint.matches("[a-f0-9]{64}")) {
            throw new IllegalArgumentException("opportunityFingerprint must be SHA-256 hex");
        }
        contributions = contributions == null ? List.of() : List.copyOf(contributions);
        reasonCodes = reasonCodes == null ? List.of() : List.copyOf(reasonCodes);
        provenance = provenance == null ? List.of() : List.copyOf(provenance);
    }

    public record Contribution(
            String family,
            @JsonProperty("configured_weight") double configuredWeight,
            @JsonProperty("effective_weight") double effectiveWeight,
            double score,
            double confidence,
            @JsonProperty("weighted_score") double weightedScore,
            @JsonProperty("age_seconds") long ageSeconds,
            boolean included,
            String reason,
            String fingerprint) {}
}
