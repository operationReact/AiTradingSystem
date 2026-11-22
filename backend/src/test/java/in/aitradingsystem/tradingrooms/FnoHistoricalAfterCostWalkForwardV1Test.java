package in.aitradingsystem.tradingrooms;

import static org.junit.jupiter.api.Assertions.*;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Synthetic accounting fixtures: NEVER independent market or broker evidence. */
class FnoHistoricalAfterCostWalkForwardV1Test {
    private static final String ID = "strategy.STR-181";
    private static final Instant SPLIT = Instant.parse("2026-10-11T00:00:00Z");
    private static final Instant AS_OF = Instant.parse("2026-10-20T00:00:00Z");
    private static final String HASH_A = "a".repeat(64);
    private static final String HASH_B = "b".repeat(64);

    private static FnoHistoricalAfterCostWalkForwardV1.CompletedTrade trade(
        String day, long gross, long broker, long taxes, long spread) {
        String at = day + "T";
        return new FnoHistoricalAfterCostWalkForwardV1.CompletedTrade(
            ID, "NSE_FO|TEST_OPTION",
            Instant.parse(at+"09:20:00Z"), Instant.parse(at+"09:19:59Z"),
            Instant.parse(at+"09:21:00Z"),
            Instant.parse(at+"10:00:00Z"), Instant.parse(at+"09:59:59Z"),
            Instant.parse(at+"10:01:00Z"),
            HASH_A, HASH_B, gross, broker, taxes, spread, 0, 0, 100000L);
    }

    private static List<FnoHistoricalAfterCostWalkForwardV1.CompletedTrade> clean() {
        return List.of(
            trade("2026-10-09", 12000, 300, 100, 200),
            trade("2026-10-10", -2500, 100, 100, 100),
            trade("2026-10-12", 10000, 700, 100, 200),
            trade("2026-10-13", -4000, 150, 50, 100));
    }

    @Test void computesActualAfterCostMathForSuppliedFixturesButNeverCertifiesMarket() {
        var report=FnoHistoricalAfterCostWalkForwardV1.evaluate(
            ID,AS_OF,SPLIT,true,clean());
        assertEquals("AFTER_COST_WALK_FORWARD_ACCOUNTED_RESEARCH_ONLY_NOT_E2E",report.state());
        assertEquals(2,report.trainingTrades());
        assertEquals(2,report.outOfSampleTrades());
        assertEquals(8600L,report.trainingAfterCostPnlPaise());
        assertEquals(6000L,report.outOfSampleGrossPnlPaise());
        assertEquals(1300L,report.outOfSampleCostsPaise());
        assertEquals(4700L,report.outOfSampleNetPnlPaise());
        assertEquals(4300L,report.outOfSampleMaxDrawdownPaise());
        assertEquals(1,report.outOfSampleLossTrades());
        assertFalse(report.independentHistoricalMarketE2E());
        assertFalse(report.paperLedgerE2E());
        assertFalse(report.liveBrokerE2E());
        assertFalse(report.ordersPlaced());
        assertEquals(report,FnoHistoricalAfterCostWalkForwardV1.evaluate(
            ID,AS_OF,SPLIT,true,clean()));
    }

    @Test void researchRightsAndMissingHistoryAlwaysFailClosed() {
        var denied=FnoHistoricalAfterCostWalkForwardV1.evaluate(
            ID,AS_OF,SPLIT,false,clean());
        assertEquals("SOURCE_RESEARCH_RIGHTS_NOT_ATTESTED",denied.reasonCode());
        assertEquals("BLOCKED_NOT_HISTORICAL_E2E",
            FnoHistoricalAfterCostWalkForwardV1.evaluate(ID,AS_OF,SPLIT,true,List.of()).state());
        assertEquals("TRAIN_OR_OOS_COMPLETED_TRADES_MISSING",
            FnoHistoricalAfterCostWalkForwardV1.evaluate(
                ID,AS_OF,SPLIT,true,clean().subList(0,2)).reasonCode());
    }

    @Test void rejectsWrongStrategyOrImpossibleSplit() {
        assertEquals("STRATEGY_OUTSIDE_FNO_43_SCOPE",
            FnoHistoricalAfterCostWalkForwardV1.evaluate(
                "strategy.STR-169",AS_OF,SPLIT,true,clean()).reasonCode());
        assertEquals("HISTORICAL_WALK_FORWARD_SPLIT_INVALID",
            FnoHistoricalAfterCostWalkForwardV1.evaluate(
                ID,SPLIT,AS_OF,true,clean()).reasonCode());
    }

    @Test void rejectsFutureKnownEntryQuoteAndTamperedSourceHash() {
        var first=clean().getFirst();
        var badTime=new FnoHistoricalAfterCostWalkForwardV1.CompletedTrade(
            first.strategyId(),first.contractKey(),first.entryDecisionAt(),
            first.entryDecisionAt().plusSeconds(1),first.entryFillAt(),
            first.exitDecisionAt(),first.exitSourceFirstKnownAt(),first.exitFillAt(),
            first.entrySourceSha256(),first.exitSourceSha256(),first.grossPnlPaise(),
            first.brokerageAndExchangePaise(),first.taxesPaise(),
            first.bidAskAndSlippagePaise(),first.marketImpactPaise(),
            first.financingPaise(),first.capitalAtRiskPaise());
        assertEquals("ORIGINAL_FILL_PROVENANCE_OR_ORDER_INVALID",
            FnoHistoricalAfterCostWalkForwardV1.evaluate(
                ID,AS_OF,SPLIT,true,List.of(badTime,clean().get(2))).reasonCode());
        var badHash=new FnoHistoricalAfterCostWalkForwardV1.CompletedTrade(
            first.strategyId(),first.contractKey(),first.entryDecisionAt(),
            first.entrySourceFirstKnownAt(),first.entryFillAt(),
            first.exitDecisionAt(),first.exitSourceFirstKnownAt(),first.exitFillAt(),
            "not-a-source-hash",first.exitSourceSha256(),first.grossPnlPaise(),
            first.brokerageAndExchangePaise(),first.taxesPaise(),
            first.bidAskAndSlippagePaise(),first.marketImpactPaise(),
            first.financingPaise(),first.capitalAtRiskPaise());
        assertEquals("ORIGINAL_FILL_PROVENANCE_OR_ORDER_INVALID",
            FnoHistoricalAfterCostWalkForwardV1.evaluate(
                ID,AS_OF,SPLIT,true,List.of(badHash,clean().get(2))).reasonCode());
    }

    @Test void rejectsOverlappingFillsAndBoundaryCrossing() {
        var t=clean().getFirst();
        assertEquals("ORIGINAL_FILL_PROVENANCE_OR_ORDER_INVALID",
            FnoHistoricalAfterCostWalkForwardV1.evaluate(
                ID,AS_OF,SPLIT,true,List.of(t,t,clean().get(2))).reasonCode());
        var crossing=new FnoHistoricalAfterCostWalkForwardV1.CompletedTrade(
            ID,"NSE_FO|TEST_OPTION",
            Instant.parse("2026-10-10T09:20:00Z"),
            Instant.parse("2026-10-10T09:19:59Z"),
            Instant.parse("2026-10-10T09:21:00Z"),
            Instant.parse("2026-10-12T09:55:00Z"),
            Instant.parse("2026-10-12T09:54:59Z"),
            Instant.parse("2026-10-12T10:00:00Z"),
            HASH_A,HASH_B,1000,100,100,100,0,0,100000);
        assertEquals("TRAIN_OOS_TRADE_CROSSES_BOUNDARY",
            FnoHistoricalAfterCostWalkForwardV1.evaluate(
                ID,AS_OF,SPLIT,true,List.of(crossing,clean().get(3))).reasonCode());
    }

    @Test void negativeCostsAndOverflowAreBlockedRatherThanInventingPnl() {
        var t=trade("2026-10-09",12000,-1,0,0);
        assertEquals("ORIGINAL_FILL_PROVENANCE_OR_ORDER_INVALID",
            FnoHistoricalAfterCostWalkForwardV1.evaluate(
                ID,AS_OF,SPLIT,true,List.of(t,clean().get(2))).reasonCode());
        var overflow=trade("2026-10-12",0,Long.MAX_VALUE,1,0);
        assertEquals("AFTER_COST_ACCOUNTING_OVERFLOW",
            FnoHistoricalAfterCostWalkForwardV1.evaluate(
                ID,AS_OF,SPLIT,true,List.of(clean().get(0),overflow)).reasonCode());
    }
}
