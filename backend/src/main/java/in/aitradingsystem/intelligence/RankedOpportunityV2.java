package in.aitradingsystem.intelligence;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * Java binding for the research-only ranked-opportunity.v2 contract.
 *
 * <p>The contract carries cross-sectional ranking, calibration, cost, uncertainty, regime,
 * liquidity and risk/reward evidence. It is intentionally unable to authorize capital or express
 * an executable status; the Java risk/execution boundary remains separate.</p>
 */
public record RankedOpportunityV2(
        @JsonProperty("contract_version") String contractVersion,
        @JsonProperty("universe_id") String universeId,
        @JsonProperty("universe_version") String universeVersion,
        @JsonProperty("instrument_id") String instrumentId,
        String symbol,
        @JsonProperty("as_of") Instant asOf,
        @JsonProperty("data_cutoff") Instant dataCutoff,
        @JsonProperty("valid_until") Instant validUntil,
        String action,
        String state,
        int rank,
        double percentile,
        @JsonProperty("sector_rank") int sectorRank,
        @JsonProperty("liquidity_rank") int liquidityRank,
        String sector,
        @JsonProperty("calibrated_probability") Double calibratedProbability,
        @JsonProperty("expected_net_edge_bps") double expectedNetEdgeBps,
        @JsonProperty("estimated_round_trip_cost_bps") double estimatedRoundTripCostBps,
        @JsonProperty("uncertainty_score") double uncertaintyScore,
        @JsonProperty("uncertainty_level") String uncertaintyLevel,
        @JsonProperty("disagreement_score") double disagreementScore,
        String regime,
        @JsonProperty("regime_fit") double regimeFit,
        @JsonProperty("adv_notional") double advNotional,
        @JsonProperty("liquidity_percentile") double liquidityPercentile,
        @JsonProperty("risk_reward_inputs") RiskRewardInputs riskRewardInputs,
        @JsonProperty("reason_codes") List<String> reasonCodes,
        @JsonProperty("invalidation_conditions") List<String> invalidationConditions,
        @JsonProperty("source_candidate_fingerprint") String sourceCandidateFingerprint,
        @JsonProperty("source_ranking_row_fingerprint") String sourceRankingRowFingerprint,
        @JsonProperty("source_concentration_score_fingerprint") String sourceConcentrationScoreFingerprint,
        @JsonProperty("opportunity_fingerprint") String opportunityFingerprint,
        String status,
        @JsonProperty("promotion_allowed") boolean promotionAllowed,
        @JsonProperty("can_authorize_capital") boolean canAuthorizeCapital) {

    public static final String VERSION = "ranked-opportunity.v2";
    public static final String STATUS = "RANKED_OPPORTUNITY_NOT_EXECUTABLE";

    public RankedOpportunityV2 {
        if (!VERSION.equals(contractVersion)) {
            throw new IllegalArgumentException("Unsupported ranked opportunity version");
        }
        requireText(universeId, "universeId");
        requireHash(universeVersion, "universeVersion");
        requireText(instrumentId, "instrumentId");
        requireText(symbol, "symbol");
        Objects.requireNonNull(asOf, "asOf");
        Objects.requireNonNull(dataCutoff, "dataCutoff");
        Objects.requireNonNull(validUntil, "validUntil");
        requireText(action, "action");
        requireText(state, "state");
        requireText(sector, "sector");
        requireText(regime, "regime");
        requireText(uncertaintyLevel, "uncertaintyLevel");
        Objects.requireNonNull(riskRewardInputs, "riskRewardInputs");
        requireText(status, "status");

        if (dataCutoff.isAfter(asOf)) {
            throw new IllegalArgumentException("dataCutoff cannot be after asOf");
        }
        if (!validUntil.isAfter(asOf)) {
            throw new IllegalArgumentException("validUntil must be after asOf");
        }
        if (!List.of("BUY", "WATCH").contains(action)) {
            throw new IllegalArgumentException("Unsupported ranked opportunity action");
        }
        if (!List.of("CANDIDATE", "ABSTAIN").contains(state)) {
            throw new IllegalArgumentException("Unsupported ranked opportunity state");
        }
        if (rank < 1 || sectorRank < 1 || liquidityRank < 1) {
            throw new IllegalArgumentException("rank values must be positive");
        }
        requireUnitInterval(percentile, "percentile");
        requireUnitInterval(uncertaintyScore, "uncertaintyScore");
        requireUnitInterval(disagreementScore, "disagreementScore");
        requireUnitInterval(regimeFit, "regimeFit");
        requireUnitInterval(liquidityPercentile, "liquidityPercentile");
        if (!List.of("LOW", "MEDIUM", "HIGH").contains(uncertaintyLevel)) {
            throw new IllegalArgumentException("Unsupported uncertainty level");
        }
        if (!Double.isFinite(expectedNetEdgeBps)) {
            throw new IllegalArgumentException("expectedNetEdgeBps must be finite");
        }
        if (!Double.isFinite(estimatedRoundTripCostBps) || estimatedRoundTripCostBps < 0.0) {
            throw new IllegalArgumentException("estimatedRoundTripCostBps must be non-negative");
        }
        if (!Double.isFinite(advNotional) || advNotional <= 0.0) {
            throw new IllegalArgumentException("advNotional must be positive");
        }
        if ("CANDIDATE".equals(state)) {
            if (calibratedProbability == null) {
                throw new IllegalArgumentException("CANDIDATE requires calibratedProbability");
            }
            requireUnitInterval(calibratedProbability, "calibratedProbability");
        } else if (calibratedProbability != null) {
            throw new IllegalArgumentException("ABSTAIN must withhold calibratedProbability");
        }
        if ("ABSTAIN".equals(state) && !"WATCH".equals(action)) {
            throw new IllegalArgumentException("ABSTAIN must use WATCH action");
        }

        requireHash(sourceCandidateFingerprint, "sourceCandidateFingerprint");
        requireHash(sourceRankingRowFingerprint, "sourceRankingRowFingerprint");
        requireHash(sourceConcentrationScoreFingerprint, "sourceConcentrationScoreFingerprint");
        requireHash(opportunityFingerprint, "opportunityFingerprint");
        if (promotionAllowed) {
            throw new IllegalArgumentException("Ranked opportunity cannot allow promotion");
        }
        if (canAuthorizeCapital) {
            throw new IllegalArgumentException("Ranked opportunity cannot authorize capital");
        }
        if (!STATUS.equals(status)) {
            throw new IllegalArgumentException("Ranked opportunity must remain non-executable");
        }

        reasonCodes = reasonCodes == null ? List.of() : List.copyOf(reasonCodes);
        invalidationConditions = invalidationConditions == null
                ? List.of()
                : List.copyOf(invalidationConditions);
        if ("ABSTAIN".equals(state) && reasonCodes.isEmpty()) {
            throw new IllegalArgumentException("ABSTAIN requires at least one reason code");
        }
    }

    private static void requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " is required");
        }
    }

    private static void requireHash(String value, String field) {
        if (value == null || !value.matches("[a-f0-9]{64}")) {
            throw new IllegalArgumentException(field + " must be SHA-256 hex");
        }
    }

    private static void requireUnitInterval(double value, String field) {
        if (!Double.isFinite(value) || value < 0.0 || value > 1.0) {
            throw new IllegalArgumentException(field + " must be within [0, 1]");
        }
    }

    /** Risk/reward inputs from the versioned target policy; descriptive only. */
    public record RiskRewardInputs(
            @JsonProperty("upper_barrier_bps") double upperBarrierBps,
            @JsonProperty("lower_barrier_bps") double lowerBarrierBps,
            @JsonProperty("horizon_bars") int horizonBars,
            @JsonProperty("reward_risk_ratio") double rewardRiskRatio) {

        public RiskRewardInputs {
            if (!Double.isFinite(upperBarrierBps) || upperBarrierBps <= 0.0) {
                throw new IllegalArgumentException("upperBarrierBps must be positive");
            }
            if (!Double.isFinite(lowerBarrierBps) || lowerBarrierBps <= 0.0) {
                throw new IllegalArgumentException("lowerBarrierBps must be positive");
            }
            if (horizonBars < 1) {
                throw new IllegalArgumentException("horizonBars must be positive");
            }
            double expectedRatio = upperBarrierBps / lowerBarrierBps;
            if (!Double.isFinite(rewardRiskRatio)
                    || Math.abs(rewardRiskRatio - expectedRatio) > 1.0e-12) {
                throw new IllegalArgumentException("rewardRiskRatio must equal upper/lower barrier ratio");
            }
        }
    }
}
