package in.aitradingsystem.scanner;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import in.aitradingsystem.intelligence.RankedOpportunityV2;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.Test;

class AiScannerServiceTest {
    private static final Instant AS_OF = Instant.parse("2026-11-03T04:00:00Z");
    private static final Instant NOW = Instant.parse("2026-11-03T04:02:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

    @Test
    void emptyProviderReturnsSuccessfulZeroEligibleSnapshot() {
        AiScannerService service = new AiScannerService(List::of, CLOCK);

        AiScannerService.ScannerSnapshot snapshot =
                service.scan(AiScannerService.ScannerQuery.defaults());

        assertEquals(AiScannerService.SourceStatus.NO_RANKED_DATA, snapshot.sourceStatus());
        assertEquals(0, snapshot.sourceCount());
        assertEquals(0, snapshot.filteredCount());
        assertEquals(0, snapshot.eligibleCount());
        assertTrue(snapshot.zeroEligibleTrades());
        assertTrue(snapshot.opportunities().isEmpty());
        assertNull(snapshot.universeId());
        assertFalse(snapshot.canAuthorizeCapital());
    }

    @Test
    void defaultSortUsesExpectedNetEdgeAndKeepsSelectiveStatesExplicit() {
        List<RankedOpportunityV2> source = List.of(
                opportunity("AAA", 1, 20.0, 0.90, "BUY", "CANDIDATE", List.of(), activeUntil()),
                opportunity("BBB", 2, 40.0, null, "WATCH", "ABSTAIN", List.of("UNCERTAINTY_TOO_HIGH"), activeUntil()),
                opportunity("CCC", 3, 30.0, 0.75, "WATCH", "CANDIDATE", List.of(), activeUntil()),
                opportunity("DDD", 4, 25.0, 0.80, "BUY", "CANDIDATE", List.of("RISK_BLOCKED"), activeUntil()),
                opportunity("EEE", 5, 50.0, 0.85, "BUY", "CANDIDATE", List.of(), AS_OF.plusSeconds(60)));
        AiScannerService service = new AiScannerService(() -> source, CLOCK);

        AiScannerService.ScannerSnapshot snapshot =
                service.scan(AiScannerService.ScannerQuery.defaults());

        assertEquals(List.of("EEE", "BBB", "CCC", "DDD", "AAA"), snapshot.opportunities().stream()
                .map(AiScannerService.OpportunityView::symbol)
                .toList());
        assertEquals(AiScannerService.PresentationState.STALE, snapshot.opportunities().get(0).state());
        assertEquals(AiScannerService.PresentationState.ABSTAIN, snapshot.opportunities().get(1).state());
        assertEquals(AiScannerService.PresentationState.WATCH, snapshot.opportunities().get(2).state());
        assertEquals(AiScannerService.PresentationState.RISK_BLOCKED, snapshot.opportunities().get(3).state());
        assertEquals(AiScannerService.PresentationState.ELIGIBLE, snapshot.opportunities().get(4).state());
        assertEquals(1, snapshot.eligibleCount());
        assertFalse(snapshot.zeroEligibleTrades());
    }

    @Test
    void filtersAndLiquiditySortUseOnlyExplicitEvidence() {
        List<RankedOpportunityV2> source = List.of(
                opportunity("AAA", 1, 30.0, 0.80, "BUY", "CANDIDATE", List.of(), activeUntil(), "Technology", 0.55, "LOW", 0.90),
                opportunity("BBB", 2, 25.0, 0.70, "BUY", "CANDIDATE", List.of(), activeUntil(), "Technology", 0.95, "LOW", 0.80),
                opportunity("CCC", 3, 35.0, 0.90, "BUY", "CANDIDATE", List.of(), activeUntil(), "Financial Services", 0.85, "MEDIUM", 0.95));
        AiScannerService service = new AiScannerService(() -> source, CLOCK);

        AiScannerService.ScannerSnapshot snapshot = service.scan(new AiScannerService.ScannerQuery(
                AiScannerService.PresentationState.ELIGIBLE,
                "technology",
                AiScannerService.UncertaintyLevel.LOW,
                0.75,
                0.50,
                AiScannerService.ScannerSort.LIQUIDITY,
                10));

        assertEquals(List.of("BBB", "AAA"), snapshot.opportunities().stream()
                .map(AiScannerService.OpportunityView::symbol)
                .toList());
        assertEquals(2, snapshot.eligibleCount());
        assertFalse(snapshot.zeroEligibleTrades());
    }

    @Test
    void probabilitySortLeavesAbstainNullAndDoesNotExposeFakeConfidence() throws Exception {
        List<RankedOpportunityV2> source = List.of(
                opportunity("AAA", 1, 20.0, 0.70, "BUY", "CANDIDATE", List.of(), activeUntil()),
                opportunity("BBB", 2, 50.0, null, "WATCH", "ABSTAIN", List.of("UNCERTAINTY_TOO_HIGH"), activeUntil()),
                opportunity("CCC", 3, 15.0, 0.90, "BUY", "CANDIDATE", List.of(), activeUntil()));
        AiScannerService service = new AiScannerService(() -> source, CLOCK);

        AiScannerService.ScannerSnapshot snapshot = service.scan(new AiScannerService.ScannerQuery(
                null,
                null,
                null,
                null,
                null,
                AiScannerService.ScannerSort.CALIBRATED_PROBABILITY,
                10));

        assertEquals(List.of("CCC", "AAA", "BBB"), snapshot.opportunities().stream()
                .map(AiScannerService.OpportunityView::symbol)
                .toList());
        assertNull(snapshot.opportunities().get(2).calibratedProbability());
        String json = new ObjectMapper().findAndRegisterModules().writeValueAsString(snapshot);
        assertTrue(json.contains("\"calibrated_probability\""));
        assertFalse(json.contains("\"confidence\""));
        assertFalse(json.contains("canPlaceOrder"));
    }

    @Test
    void zeroEligibleIsValidEvenWhenWatchAndAbstainEvidenceExists() {
        List<RankedOpportunityV2> source = List.of(
                opportunity("AAA", 1, 10.0, null, "WATCH", "ABSTAIN", List.of("NEGATIVE_NET_EDGE"), activeUntil()),
                opportunity("BBB", 2, 15.0, 0.60, "WATCH", "CANDIDATE", List.of(), activeUntil()));
        AiScannerService service = new AiScannerService(() -> source, CLOCK);

        AiScannerService.ScannerSnapshot snapshot =
                service.scan(AiScannerService.ScannerQuery.defaults());

        assertEquals(2, snapshot.filteredCount());
        assertEquals(0, snapshot.eligibleCount());
        assertTrue(snapshot.zeroEligibleTrades());
        assertEquals(AiScannerService.SourceStatus.READY, snapshot.sourceStatus());
    }

    @Test
    void mixedUniverseOrAsOfProviderOutputFailsClosed() {
        RankedOpportunityV2 first = opportunity(
                "AAA", 1, 20.0, 0.8, "BUY", "CANDIDATE", List.of(), activeUntil());
        RankedOpportunityV2 mixed = new RankedOpportunityV2(
                RankedOpportunityV2.VERSION,
                "OTHER_UNIVERSE",
                "f".repeat(64),
                "NSE:OTHER",
                "OTHER",
                AS_OF,
                AS_OF.minusSeconds(60),
                activeUntil(),
                "BUY",
                "CANDIDATE",
                2,
                0.5,
                1,
                1,
                "Technology",
                0.7,
                10.0,
                5.0,
                0.2,
                "LOW",
                0.1,
                "TRENDING",
                0.8,
                10_000_000.0,
                0.5,
                new RankedOpportunityV2.RiskRewardInputs(50.0, 25.0, 12, 2.0),
                List.of("POSITIVE_NET_EDGE"),
                List.of("STALE_EVIDENCE"),
                "1".repeat(64),
                "2".repeat(64),
                "3".repeat(64),
                "4".repeat(64),
                RankedOpportunityV2.STATUS,
                false,
                false);
        AiScannerService service = new AiScannerService(() -> List.of(first, mixed), CLOCK);

        assertThrows(
                IllegalStateException.class,
                () -> service.scan(AiScannerService.ScannerQuery.defaults()));
    }

    @Test
    void queryBoundsFailClosed() {
        AiScannerService service = new AiScannerService(List::of, CLOCK);

        assertThrows(
                IllegalArgumentException.class,
                () -> service.scan(new AiScannerService.ScannerQuery(
                        null, null, null, null, null, AiScannerService.ScannerSort.EXPECTED_NET_EDGE, 0)));
        assertThrows(
                IllegalArgumentException.class,
                () -> service.scan(new AiScannerService.ScannerQuery(
                        null, null, null, 1.01, null, AiScannerService.ScannerSort.EXPECTED_NET_EDGE, 10)));
    }

    private static Instant activeUntil() {
        return NOW.plusSeconds(300);
    }

    private static RankedOpportunityV2 opportunity(
            String symbol,
            int rank,
            double edge,
            Double probability,
            String action,
            String state,
            List<String> reasons,
            Instant validUntil) {
        return opportunity(
                symbol,
                rank,
                edge,
                probability,
                action,
                state,
                reasons,
                validUntil,
                "Technology",
                0.80,
                "LOW",
                0.85);
    }

    private static RankedOpportunityV2 opportunity(
            String symbol,
            int rank,
            double edge,
            Double probability,
            String action,
            String state,
            List<String> reasons,
            Instant validUntil,
            String sector,
            double liquidityPercentile,
            String uncertainty,
            double regimeFit) {
        return new RankedOpportunityV2(
                RankedOpportunityV2.VERSION,
                "INDIA_NSE_NIFTY500_LIQUID",
                "a".repeat(64),
                "NSE:" + symbol,
                symbol,
                AS_OF,
                AS_OF.minusSeconds(60),
                validUntil,
                action,
                state,
                rank,
                Math.max(0.0, 1.0 - (rank * 0.1)),
                1,
                rank,
                sector,
                probability,
                edge,
                10.0,
                "HIGH".equals(uncertainty) ? 0.7 : "MEDIUM".equals(uncertainty) ? 0.4 : 0.1,
                uncertainty,
                0.1,
                "TRENDING",
                regimeFit,
                10_000_000.0 + rank,
                liquidityPercentile,
                new RankedOpportunityV2.RiskRewardInputs(50.0, 25.0, 12, 2.0),
                reasons,
                List.of("STALE_EVIDENCE"),
                hash(rank),
                hash(rank + 5),
                hash(rank + 9),
                hash(rank + 12),
                RankedOpportunityV2.STATUS,
                false,
                false);
    }

    private static String hash(int value) {
        return String.format("%064x", value);
    }
}
