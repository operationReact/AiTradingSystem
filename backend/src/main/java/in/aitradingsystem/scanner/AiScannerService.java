package in.aitradingsystem.scanner;

import com.fasterxml.jackson.annotation.JsonProperty;
import in.aitradingsystem.intelligence.RankedOpportunityV2;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

@Service
public class AiScannerService {
    public static final String SNAPSHOT_VERSION = "ai-scanner-snapshot.v1";

    private final RankedOpportunityProvider provider;
    private final Clock clock;

    @Autowired
    public AiScannerService(RankedOpportunityProvider provider) {
        this(provider, Clock.systemUTC());
    }

    AiScannerService(RankedOpportunityProvider provider, Clock clock) {
        this.provider = Objects.requireNonNull(provider, "provider");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public ScannerSnapshot scan(ScannerQuery query) {
        Objects.requireNonNull(query, "query");
        validateQuery(query);

        Instant now = clock.instant();
        List<RankedOpportunityV2> source = safeSource();
        validateFrozenCrossSection(source);

        List<OpportunityView> projected = source.stream()
                .map(item -> OpportunityView.from(item, presentationState(item, now), now))
                .filter(item -> query.state() == null || item.state() == query.state())
                .filter(item -> query.sector() == null
                        || item.sector().equalsIgnoreCase(query.sector().trim()))
                .filter(item -> query.uncertaintyLevel() == null
                        || item.uncertaintyLevel().equals(query.uncertaintyLevel().name()))
                .filter(item -> query.minRegimeFit() == null
                        || item.regimeFit() >= query.minRegimeFit())
                .filter(item -> query.minLiquidityPercentile() == null
                        || item.liquidityPercentile() >= query.minLiquidityPercentile())
                .sorted(comparator(query.sort()))
                .limit(query.limit())
                .toList();

        int eligibleCount = (int) projected.stream()
                .filter(item -> item.state() == PresentationState.ELIGIBLE)
                .count();
        RankedOpportunityV2 first = source.isEmpty() ? null : source.getFirst();
        return new ScannerSnapshot(
                SNAPSHOT_VERSION,
                now,
                first == null ? null : first.asOf(),
                first == null ? null : first.universeId(),
                first == null ? null : first.universeVersion(),
                source.isEmpty() ? SourceStatus.NO_RANKED_DATA : SourceStatus.READY,
                source.size(),
                projected.size(),
                eligibleCount,
                eligibleCount == 0,
                projected,
                false);
    }

    private List<RankedOpportunityV2> safeSource() {
        List<RankedOpportunityV2> opportunities = provider.currentOpportunities();
        if (opportunities == null) {
            throw new IllegalStateException("Ranked opportunity provider returned null");
        }
        List<RankedOpportunityV2> copy = new ArrayList<>(opportunities.size());
        for (RankedOpportunityV2 opportunity : opportunities) {
            copy.add(Objects.requireNonNull(opportunity, "ranked opportunity"));
        }
        return List.copyOf(copy);
    }

    private static void validateFrozenCrossSection(List<RankedOpportunityV2> source) {
        if (source.isEmpty()) {
            return;
        }
        RankedOpportunityV2 first = source.getFirst();
        for (RankedOpportunityV2 item : source) {
            if (!first.universeId().equals(item.universeId())
                    || !first.universeVersion().equals(item.universeVersion())
                    || !first.asOf().equals(item.asOf())) {
                throw new IllegalStateException(
                        "AI scanner provider must return one frozen universe/as-of cross-section");
            }
            if (item.promotionAllowed() || item.canAuthorizeCapital()) {
                throw new IllegalStateException("AI scanner source cannot carry promotion/capital authority");
            }
            if (!RankedOpportunityV2.STATUS.equals(item.status())) {
                throw new IllegalStateException("AI scanner source must remain non-executable");
            }
        }
    }

    private static PresentationState presentationState(RankedOpportunityV2 item, Instant now) {
        if (!item.validUntil().isAfter(now)) {
            return PresentationState.STALE;
        }
        if (item.reasonCodes().stream().anyMatch("RISK_BLOCKED"::equals)) {
            return PresentationState.RISK_BLOCKED;
        }
        if ("ABSTAIN".equals(item.state())) {
            return PresentationState.ABSTAIN;
        }
        if ("WATCH".equals(item.action())) {
            return PresentationState.WATCH;
        }
        return PresentationState.ELIGIBLE;
    }

    private static Comparator<OpportunityView> comparator(ScannerSort sort) {
        Comparator<OpportunityView> primary = switch (sort) {
            case EXPECTED_NET_EDGE -> Comparator.comparingDouble(OpportunityView::expectedNetEdgeBps)
                    .reversed();
            case CALIBRATED_PROBABILITY -> Comparator.comparing(
                            OpportunityView::calibratedProbability,
                            Comparator.nullsLast(Comparator.reverseOrder()))
                    .thenComparingInt(OpportunityView::rank);
            case OPPORTUNITY_RANK -> Comparator.comparingInt(OpportunityView::rank);
            case LIQUIDITY -> Comparator.comparingDouble(OpportunityView::liquidityPercentile)
                    .reversed();
        };
        return primary.thenComparingInt(OpportunityView::rank).thenComparing(OpportunityView::instrumentId);
    }

    private static void validateQuery(ScannerQuery query) {
        if (query.limit() < 1 || query.limit() > 100) {
            throw new IllegalArgumentException("limit must be within [1, 100]");
        }
        validateOptionalUnitInterval(query.minRegimeFit(), "minRegimeFit");
        validateOptionalUnitInterval(query.minLiquidityPercentile(), "minLiquidityPercentile");
        if (query.sector() != null && query.sector().isBlank()) {
            throw new IllegalArgumentException("sector cannot be blank");
        }
    }

    private static void validateOptionalUnitInterval(Double value, String field) {
        if (value != null && (!Double.isFinite(value) || value < 0.0 || value > 1.0)) {
            throw new IllegalArgumentException(field + " must be within [0, 1]");
        }
    }

    public enum PresentationState {
        ELIGIBLE,
        WATCH,
        ABSTAIN,
        RISK_BLOCKED,
        STALE
    }

    public enum UncertaintyLevel {
        LOW,
        MEDIUM,
        HIGH
    }

    public enum ScannerSort {
        EXPECTED_NET_EDGE,
        CALIBRATED_PROBABILITY,
        OPPORTUNITY_RANK,
        LIQUIDITY
    }

    public enum SourceStatus {
        READY,
        NO_RANKED_DATA
    }

    public record ScannerQuery(
            PresentationState state,
            String sector,
            UncertaintyLevel uncertaintyLevel,
            Double minRegimeFit,
            Double minLiquidityPercentile,
            ScannerSort sort,
            int limit) {
        public ScannerQuery {
            sort = sort == null ? ScannerSort.EXPECTED_NET_EDGE : sort;
        }

        public static ScannerQuery defaults() {
            return new ScannerQuery(null, null, null, null, null, ScannerSort.EXPECTED_NET_EDGE, 50);
        }
    }

    public record ScannerSnapshot(
            @JsonProperty("contract_version") String contractVersion,
            @JsonProperty("generated_at") Instant generatedAt,
            @JsonProperty("as_of") Instant asOf,
            @JsonProperty("universe_id") String universeId,
            @JsonProperty("universe_version") String universeVersion,
            @JsonProperty("source_status") SourceStatus sourceStatus,
            @JsonProperty("source_count") int sourceCount,
            @JsonProperty("filtered_count") int filteredCount,
            @JsonProperty("eligible_count") int eligibleCount,
            @JsonProperty("zero_eligible_trades") boolean zeroEligibleTrades,
            List<OpportunityView> opportunities,
            @JsonProperty("can_authorize_capital") boolean canAuthorizeCapital) {
        public ScannerSnapshot {
            opportunities = List.copyOf(opportunities);
            if (eligibleCount < 0 || filteredCount < 0 || sourceCount < 0) {
                throw new IllegalArgumentException("scanner counts cannot be negative");
            }
            if (eligibleCount > filteredCount || filteredCount > sourceCount) {
                throw new IllegalArgumentException("scanner counts are inconsistent");
            }
            if (zeroEligibleTrades != (eligibleCount == 0)) {
                throw new IllegalArgumentException("zeroEligibleTrades must match eligibleCount");
            }
            if (canAuthorizeCapital) {
                throw new IllegalArgumentException("AI scanner snapshot cannot authorize capital");
            }
        }
    }

    public record OpportunityView(
            @JsonProperty("instrument_id") String instrumentId,
            String symbol,
            PresentationState state,
            String direction,
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
            @JsonProperty("valid_until") Instant validUntil,
            @JsonProperty("time_to_expiry_seconds") long timeToExpirySeconds,
            @JsonProperty("risk_reward_inputs") RankedOpportunityV2.RiskRewardInputs riskRewardInputs,
            @JsonProperty("reason_codes") List<String> reasonCodes,
            @JsonProperty("invalidation_conditions") List<String> invalidationConditions,
            @JsonProperty("opportunity_fingerprint") String opportunityFingerprint,
            @JsonProperty("can_authorize_capital") boolean canAuthorizeCapital) {

        static OpportunityView from(
                RankedOpportunityV2 source, PresentationState state, Instant now) {
            long seconds = Math.max(0L, Duration.between(now, source.validUntil()).getSeconds());
            return new OpportunityView(
                    source.instrumentId(),
                    source.symbol(),
                    state,
                    source.action(),
                    source.rank(),
                    source.percentile(),
                    source.sectorRank(),
                    source.liquidityRank(),
                    source.sector(),
                    source.calibratedProbability(),
                    source.expectedNetEdgeBps(),
                    source.estimatedRoundTripCostBps(),
                    source.uncertaintyScore(),
                    source.uncertaintyLevel().toUpperCase(Locale.ROOT),
                    source.disagreementScore(),
                    source.regime(),
                    source.regimeFit(),
                    source.advNotional(),
                    source.liquidityPercentile(),
                    source.validUntil(),
                    seconds,
                    source.riskRewardInputs(),
                    source.reasonCodes(),
                    source.invalidationConditions(),
                    source.opportunityFingerprint(),
                    false);
        }

        public OpportunityView {
            reasonCodes = List.copyOf(reasonCodes);
            invalidationConditions = List.copyOf(invalidationConditions);
            if (canAuthorizeCapital) {
                throw new IllegalArgumentException("AI scanner opportunity cannot authorize capital");
            }
        }
    }
}
