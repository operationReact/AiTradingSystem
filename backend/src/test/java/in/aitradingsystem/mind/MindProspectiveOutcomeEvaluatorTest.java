package in.aitradingsystem.mind;

import static org.junit.jupiter.api.Assertions.*;

import in.aitradingsystem.marketdata.ExpectedCandleSlot;
import in.aitradingsystem.marketdata.HistoricalCandleInterval;
import in.aitradingsystem.marketdata.MarketCandle;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class MindProspectiveOutcomeEvaluatorTest {
    private final MindProspectiveOutcomeEvaluator subject = new MindProspectiveOutcomeEvaluator();
    private static final Instant SIGNAL = Instant.parse("2026-11-11T05:25:00Z");
    private static final Instant RECORDED = SIGNAL.plusSeconds(360);
    private static final Instant NOW = Instant.parse("2026-11-11T10:16:00Z");
    private static final UUID CATALOGUE = UUID.randomUUID();
    private static final UUID COHORT = UUID.randomUUID();
    private static final String DIGEST = "a".repeat(64);
    private static final String FINGERPRINT = "b".repeat(64);
    private static final Instant NEXT = SIGNAL.plusSeconds(300);

    private static MindProspectiveOutcomeRepository.Receipt receipt() {
        return new MindProspectiveOutcomeRepository.Receipt(
            COHORT, 1, SIGNAL, RECORDED, FINGERPRINT, DIGEST,
            new BigDecimal("100"), new BigDecimal("99"),
            new BigDecimal("102"), 50001L, CATALOGUE, "NSE",
            "STOCK1-EQ", "token1");
    }

    private static MarketCandle bar(Instant at, String source, BigDecimal open,
                                    BigDecimal high, BigDecimal low, BigDecimal close,
                                    Instant fetched) {
        return new MarketCandle(1, 50001L, "NSE", "STOCK1-EQ",
            "token1", CATALOGUE, "FIVE_MINUTE", at, open, high,
            low, close, 1234, source, fetched);
    }

    private static List<ExpectedCandleSlot> slots() {
        return List.of(
            new ExpectedCandleSlot(NEXT, HistoricalCandleInterval.FIVE_MINUTE),
            new ExpectedCandleSlot(NEXT.plusSeconds(300), HistoricalCandleInterval.FIVE_MINUTE));
    }

    @Test
    void stopWinsSameBarStopAndTargetAndCostsMakeResultWorse() {
        var candle = bar(NEXT, "ANGEL_ONE_SMARTAPI",
            new BigDecimal("100"), new BigDecimal("103"),
            new BigDecimal("98"), new BigDecimal("101"), NEXT.plusSeconds(330));
        var result = subject.evaluate(receipt(), List.of(candle), slots(), NOW);
        assertTrue(result.isPresent());
        assertEquals("STOP", result.get().exitReason());
        assertEquals(new BigDecimal("99"), result.get().rawExit());
        assertEquals(new BigDecimal("-1.0000"), result.get().grossReturnPercent());
        assertTrue(result.get().illustrativeNetReturnPercent().compareTo(
            result.get().grossReturnPercent()) < 0);
        assertEquals(1, result.get().sourceBars());
        assertTrue(result.get().outcomeSourceDigest().matches("[a-f0-9]{64}"));
    }

    @Test
    void targetRequiresValidBarAndDoesNotAuthorizeTrade() {
        var candle = bar(NEXT, "ANGEL_ONE_SMARTAPI",
            new BigDecimal("100"), new BigDecimal("104"),
            new BigDecimal("99.50"), new BigDecimal("102"), NEXT.plusSeconds(330));
        var result = subject.evaluate(receipt(), List.of(candle), slots(), NOW).orElseThrow();
        assertEquals("TARGET", result.exitReason());
        assertEquals(new BigDecimal("102"), result.rawExit());
        assertEquals(new BigDecimal("2.0000"), result.grossReturnPercent());
    }

    @Test
    void gapThroughStopUsesWorseNextOpen() {
        var candle = bar(NEXT, "ANGEL_ONE_SMARTAPI",
            new BigDecimal("97"), new BigDecimal("100"),
            new BigDecimal("96"), new BigDecimal("98"), NEXT.plusSeconds(330));
        assertEquals(new BigDecimal("97"),
            subject.evaluate(receipt(), List.of(candle), slots(), NOW)
                .orElseThrow().rawExit());
    }

    @Test
    void missingNextBarCannotBeFilledFromLaterPrice() {
        var late = bar(NEXT.plusSeconds(300), "ANGEL_ONE_SMARTAPI",
            new BigDecimal("100"), new BigDecimal("103"),
            new BigDecimal("99.5"), new BigDecimal("102"),
            NEXT.plusSeconds(630));
        assertTrue(subject.evaluate(receipt(), List.of(late), slots(), NOW).isEmpty());
    }

    @Test
    void syntheticOrLateOrFutureSourceCannotProduceOutcome() {
        var fixture = bar(NEXT, "TEST_FIXTURE",
            new BigDecimal("100"), new BigDecimal("103"),
            new BigDecimal("98"), new BigDecimal("101"), NEXT.plusSeconds(330));
        assertTrue(subject.evaluate(receipt(), List.of(fixture), slots(), NOW).isEmpty());
        var late = bar(NEXT, "ANGEL_ONE_SMARTAPI",
            new BigDecimal("100"), new BigDecimal("103"),
            new BigDecimal("98"), new BigDecimal("101"), NEXT.plusSeconds(960));
        assertTrue(subject.evaluate(receipt(), List.of(late), slots(), NOW).isEmpty());
        var future = bar(NEXT, "ANGEL_ONE_SMARTAPI",
            new BigDecimal("100"), new BigDecimal("103"),
            new BigDecimal("98"), new BigDecimal("101"), NOW.plusSeconds(60));
        assertTrue(subject.evaluate(receipt(), List.of(future), slots(), NOW).isEmpty());
    }

    @Test
    void noTargetOrStopRequiresCompleteSessionAndNeverFillsGaps() {
        var first = bar(NEXT, "ANGEL_ONE_SMARTAPI",
            new BigDecimal("100"), new BigDecimal("101"),
            new BigDecimal("99.5"), new BigDecimal("100"), NEXT.plusSeconds(330));
        assertTrue(subject.evaluate(receipt(), List.of(first), slots(), NOW).isEmpty());
        var last = bar(NEXT.plusSeconds(300), "ANGEL_ONE_SMARTAPI",
            new BigDecimal("100"), new BigDecimal("101"),
            new BigDecimal("99.5"), new BigDecimal("100.5"), NEXT.plusSeconds(630));
        var outcome = subject.evaluate(receipt(), List.of(first, last), slots(), NOW).orElseThrow();
        assertEquals("SESSION_CLOSE", outcome.exitReason());
        assertEquals(2, outcome.sourceBars());
    }
}
