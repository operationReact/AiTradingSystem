package in.aitradingsystem.tradingrooms;

import in.aitradingsystem.mind.MindPaperCycleService;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LiveEquityStrategyProposalServiceTest {
    private static final Instant NOW = Instant.parse("2026-11-28T09:00:00Z");

    @Test
    void acceptsFreshFilledPaperBuyWithDefinedRiskLevels() {
        assertTrue(LiveEquityStrategyProposalService.eligibleCycle(cycle(
            MindPaperCycleService.Outcome.FILLED, "BUY", new BigDecimal("101.00"),
            new BigDecimal("99.00"), new BigDecimal("104.00"), NOW.minusSeconds(3),
            NOW.minusSeconds(2), false), NOW));
    }

    @Test
    void rejectsCycleOlderThanTwoMinutesOrWithLiveOrderProvenance() {
        assertFalse(LiveEquityStrategyProposalService.eligibleCycle(cycle(
            MindPaperCycleService.Outcome.FILLED, "BUY", new BigDecimal("101.00"),
            new BigDecimal("99.00"), new BigDecimal("104.00"), NOW.minusSeconds(121),
            NOW.minusSeconds(2), false), NOW));
        assertFalse(LiveEquityStrategyProposalService.eligibleCycle(cycle(
            MindPaperCycleService.Outcome.FILLED, "BUY", new BigDecimal("101.00"),
            new BigDecimal("99.00"), new BigDecimal("104.00"), NOW.minusSeconds(3),
            NOW.minusSeconds(2), true), NOW));
    }

    @Test
    void rejectsNoFillAndInvertedRiskLevels() {
        assertFalse(LiveEquityStrategyProposalService.eligibleCycle(cycle(
            MindPaperCycleService.Outcome.NO_ENTRY, "BUY", new BigDecimal("101.00"),
            new BigDecimal("99.00"), new BigDecimal("104.00"), NOW.minusSeconds(3),
            NOW.minusSeconds(2), false), NOW));
        assertFalse(LiveEquityStrategyProposalService.eligibleCycle(cycle(
            MindPaperCycleService.Outcome.FILLED, "BUY", new BigDecimal("101.00"),
            new BigDecimal("102.00"), new BigDecimal("104.00"), NOW.minusSeconds(3),
            NOW.minusSeconds(2), false), NOW));
    }

    private static MindPaperCycleService.Cycle cycle(MindPaperCycleService.Outcome outcome,
            String side, BigDecimal limit, BigDecimal stop, BigDecimal target,
            Instant createdAt, Instant quoteAt, boolean liveOrder) {
        return new MindPaperCycleService.Cycle(UUID.randomUUID(), UUID.randomUUID(), 12345L,
            "INFY", "FIVE_MINUTE", "OPENING_RANGE_BREAKOUT_V1", "ORB_12345678",
            side, NOW.minusSeconds(300), "ANGEL_ONE_SMARTAPI", outcome, side, 10, limit, stop,
            target, new BigDecimal("100.00"), quoteAt, UUID.randomUUID(), List.of(), List.of(),
            "test-revision", createdAt,
            "STORED_BROKER_CANDLES:ANGEL_ONE_SMARTAPI + VERIFIED_LIVE_TICK", liveOrder);
    }
}
