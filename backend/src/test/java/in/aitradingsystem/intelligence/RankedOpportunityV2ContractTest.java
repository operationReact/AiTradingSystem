package in.aitradingsystem.intelligence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class RankedOpportunityV2ContractTest {

    private static final Instant AS_OF = Instant.parse("2026-11-03T04:00:00Z");
    private static final Instant DATA_CUTOFF = AS_OF.minusSeconds(60);
    private static final Instant VALID_UNTIL = AS_OF.plusSeconds(300);

    @Test
    void candidateBindsAllCrossSectionalEvidenceAndRemainsNonExecutable() {
        RankedOpportunityV2 opportunity = candidate(false, false, RankedOpportunityV2.STATUS);

        assertEquals(RankedOpportunityV2.VERSION, opportunity.contractVersion());
        assertEquals("INDIA_NSE_NIFTY500_LIQUID", opportunity.universeId());
        assertEquals(1, opportunity.rank());
        assertEquals(1, opportunity.sectorRank());
        assertEquals(2, opportunity.liquidityRank());
        assertEquals(0.82, opportunity.calibratedProbability());
        assertEquals(34.5, opportunity.expectedNetEdgeBps());
        assertEquals("LOW", opportunity.uncertaintyLevel());
        assertFalse(opportunity.promotionAllowed());
        assertFalse(opportunity.canAuthorizeCapital());
    }

    @Test
    void abstainWithholdsProbabilityAndRequiresWatchPlusReason() {
        RankedOpportunityV2 abstain = abstain(null, "WATCH", List.of("UNCERTAINTY_TOO_HIGH"));

        assertEquals("ABSTAIN", abstain.state());
        assertNull(abstain.calibratedProbability());
        assertThrows(
                IllegalArgumentException.class,
                () -> abstain(0.55, "WATCH", List.of("UNCERTAINTY_TOO_HIGH")));
        assertThrows(
                IllegalArgumentException.class,
                () -> abstain(null, "BUY", List.of("UNCERTAINTY_TOO_HIGH")));
        assertThrows(
                IllegalArgumentException.class,
                () -> abstain(null, "WATCH", List.of()));
    }

    @Test
    void contractCannotCrossPromotionOrCapitalBoundary() {
        assertThrows(
                IllegalArgumentException.class,
                () -> candidate(true, false, RankedOpportunityV2.STATUS));
        assertThrows(
                IllegalArgumentException.class,
                () -> candidate(false, true, RankedOpportunityV2.STATUS));
        assertThrows(
                IllegalArgumentException.class,
                () -> candidate(false, false, "EXECUTABLE"));
    }

    @Test
    void rejectsFutureDataCutoffAndExpiredOpportunity() {
        RankedOpportunityV2 base = candidate(false, false, RankedOpportunityV2.STATUS);

        assertThrows(
                IllegalArgumentException.class,
                () -> copy(base, AS_OF.plusSeconds(1), VALID_UNTIL, base.rank(), base.percentile()));
        assertThrows(
                IllegalArgumentException.class,
                () -> copy(base, DATA_CUTOFF, AS_OF, base.rank(), base.percentile()));
    }

    @Test
    void rankAndPercentileAreValidated() {
        RankedOpportunityV2 base = candidate(false, false, RankedOpportunityV2.STATUS);

        assertThrows(
                IllegalArgumentException.class,
                () -> copy(base, DATA_CUTOFF, VALID_UNTIL, 0, base.percentile()));
        assertThrows(
                IllegalArgumentException.class,
                () -> copy(base, DATA_CUTOFF, VALID_UNTIL, base.rank(), 1.01));
    }

    @Test
    void riskRewardRatioMustMatchTargetBarriers() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new RankedOpportunityV2.RiskRewardInputs(50.0, 25.0, 12, 1.5));
    }

    @Test
    void snakeCaseJsonDeserializesIntoTheValidatedBinding() throws Exception {
        String json = """
                {
                  "contract_version":"ranked-opportunity.v2",
                  "universe_id":"INDIA_NSE_NIFTY500_LIQUID",
                  "universe_version":"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
                  "instrument_id":"NSE:INE001A01010",
                  "symbol":"AAA",
                  "as_of":"2026-11-03T04:00:00Z",
                  "data_cutoff":"2026-11-03T03:59:00Z",
                  "valid_until":"2026-11-03T04:05:00Z",
                  "action":"BUY",
                  "state":"CANDIDATE",
                  "rank":1,
                  "percentile":0.99,
                  "sector_rank":1,
                  "liquidity_rank":2,
                  "sector":"Technology",
                  "calibrated_probability":0.82,
                  "expected_net_edge_bps":34.5,
                  "estimated_round_trip_cost_bps":10.0,
                  "uncertainty_score":0.12,
                  "uncertainty_level":"LOW",
                  "disagreement_score":0.08,
                  "regime":"TRENDING",
                  "regime_fit":0.88,
                  "adv_notional":25000000.0,
                  "liquidity_percentile":0.90,
                  "risk_reward_inputs":{
                    "upper_barrier_bps":50.0,
                    "lower_barrier_bps":25.0,
                    "horizon_bars":12,
                    "reward_risk_ratio":2.0
                  },
                  "reason_codes":["POSITIVE_NET_EDGE"],
                  "invalidation_conditions":["STALE_EVIDENCE"],
                  "source_candidate_fingerprint":"bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb",
                  "source_ranking_row_fingerprint":"cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc",
                  "source_concentration_score_fingerprint":"dddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddd",
                  "opportunity_fingerprint":"eeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeee",
                  "status":"RANKED_OPPORTUNITY_NOT_EXECUTABLE",
                  "promotion_allowed":false,
                  "can_authorize_capital":false
                }
                """;

        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        RankedOpportunityV2 opportunity = mapper.readValue(json, RankedOpportunityV2.class);

        assertEquals("AAA", opportunity.symbol());
        assertEquals(2.0, opportunity.riskRewardInputs().rewardRiskRatio());
        assertEquals(List.of("STALE_EVIDENCE"), opportunity.invalidationConditions());
    }

    private static RankedOpportunityV2 candidate(
            boolean promotionAllowed, boolean canAuthorizeCapital, String status) {
        return new RankedOpportunityV2(
                RankedOpportunityV2.VERSION,
                "INDIA_NSE_NIFTY500_LIQUID",
                "a".repeat(64),
                "NSE:INE001A01010",
                "AAA",
                AS_OF,
                DATA_CUTOFF,
                VALID_UNTIL,
                "BUY",
                "CANDIDATE",
                1,
                0.99,
                1,
                2,
                "Technology",
                0.82,
                34.5,
                10.0,
                0.12,
                "LOW",
                0.08,
                "TRENDING",
                0.88,
                25_000_000.0,
                0.90,
                new RankedOpportunityV2.RiskRewardInputs(50.0, 25.0, 12, 2.0),
                List.of("POSITIVE_NET_EDGE", "REGIME_COMPATIBLE"),
                List.of("STALE_EVIDENCE", "REGIME_CHANGED"),
                "b".repeat(64),
                "c".repeat(64),
                "d".repeat(64),
                "e".repeat(64),
                status,
                promotionAllowed,
                canAuthorizeCapital);
    }

    private static RankedOpportunityV2 abstain(
            Double probability, String action, List<String> reasonCodes) {
        return new RankedOpportunityV2(
                RankedOpportunityV2.VERSION,
                "INDIA_NSE_NIFTY500_LIQUID",
                "a".repeat(64),
                "NSE:INE002A01018",
                "BBB",
                AS_OF,
                DATA_CUTOFF,
                VALID_UNTIL,
                action,
                "ABSTAIN",
                2,
                0.75,
                1,
                2,
                "Technology",
                probability,
                -5.0,
                10.0,
                0.70,
                "HIGH",
                0.65,
                "CHOPPY",
                0.30,
                15_000_000.0,
                0.60,
                new RankedOpportunityV2.RiskRewardInputs(50.0, 25.0, 12, 2.0),
                reasonCodes,
                List.of("UNCERTAINTY_NORMALIZED"),
                "f".repeat(64),
                "1".repeat(64),
                "2".repeat(64),
                "3".repeat(64),
                RankedOpportunityV2.STATUS,
                false,
                false);
    }

    private static RankedOpportunityV2 copy(
            RankedOpportunityV2 source,
            Instant dataCutoff,
            Instant validUntil,
            int rank,
            double percentile) {
        return new RankedOpportunityV2(
                source.contractVersion(),
                source.universeId(),
                source.universeVersion(),
                source.instrumentId(),
                source.symbol(),
                source.asOf(),
                dataCutoff,
                validUntil,
                source.action(),
                source.state(),
                rank,
                percentile,
                source.sectorRank(),
                source.liquidityRank(),
                source.sector(),
                source.calibratedProbability(),
                source.expectedNetEdgeBps(),
                source.estimatedRoundTripCostBps(),
                source.uncertaintyScore(),
                source.uncertaintyLevel(),
                source.disagreementScore(),
                source.regime(),
                source.regimeFit(),
                source.advNotional(),
                source.liquidityPercentile(),
                source.riskRewardInputs(),
                source.reasonCodes(),
                source.invalidationConditions(),
                source.sourceCandidateFingerprint(),
                source.sourceRankingRowFingerprint(),
                source.sourceConcentrationScoreFingerprint(),
                source.opportunityFingerprint(),
                source.status(),
                source.promotionAllowed(),
                source.canAuthorizeCapital());
    }
}
