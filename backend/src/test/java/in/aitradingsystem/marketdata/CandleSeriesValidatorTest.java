package in.aitradingsystem.marketdata;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class CandleSeriesValidatorTest {
    private static final Instant CUTOFF = Instant.parse("2026-10-29T04:00:00Z");

    @Test
    void acceptsStrictlyAscendingPositiveSeriesAtExactAgeBoundary() {
        var result = CandleSeriesValidator.validate(List.of(
                candle(CUTOFF.minusSeconds(120)), candle(CUTOFF.minusSeconds(60))),
                CUTOFF, Duration.ofSeconds(60));
        assertTrue(result.valid());
    }

    @Test
    void rejectsDuplicateAndOutOfOrderCandles() {
        Instant time = CUTOFF.minusSeconds(60);
        var duplicate = CandleSeriesValidator.validate(List.of(candle(time), candle(time)), CUTOFF, Duration.ofMinutes(5));
        var outOfOrder = CandleSeriesValidator.validate(List.of(candle(time), candle(time.minusSeconds(60))), CUTOFF, Duration.ofMinutes(5));
        assertFalse(duplicate.valid());
        assertEquals("DUPLICATE_OR_OUT_OF_ORDER_CANDLE", duplicate.reason());
        assertFalse(outOfOrder.valid());
        assertEquals("DUPLICATE_OR_OUT_OF_ORDER_CANDLE", outOfOrder.reason());
    }

    @Test
    void rejectsFutureStaleAndNonPositiveOhlc() {
        assertEquals("FUTURE_CANDLE", CandleSeriesValidator.validate(
                List.of(candle(CUTOFF.plusSeconds(1))), CUTOFF, Duration.ofMinutes(5)).reason());
        assertEquals("STALE_CANDLE", CandleSeriesValidator.validate(
                List.of(candle(CUTOFF.minusSeconds(301))), CUTOFF, Duration.ofMinutes(5)).reason());
        assertEquals("NON_POSITIVE_OHLC", CandleSeriesValidator.validate(
                List.of(candle(CUTOFF.minusSeconds(1), BigDecimal.ZERO)), CUTOFF, Duration.ofMinutes(5)).reason());
    }

    @Test
    void deterministicReplayProducesSameValidation() {
        List<MarketCandle> series = List.of(candle(CUTOFF.minusSeconds(120)), candle(CUTOFF.minusSeconds(60)));
        assertEquals(CandleSeriesValidator.validate(series, CUTOFF, Duration.ofMinutes(5)),
                CandleSeriesValidator.validate(series, CUTOFF, Duration.ofMinutes(5)));
    }

    private static MarketCandle candle(Instant time) {
        return candle(time, new BigDecimal("100"));
    }

    private static MarketCandle candle(Instant time, BigDecimal close) {
        return new MarketCandle(1L, 1L, "NSE", "TEST-EQ", "1", null, "ONE_MINUTE", time,
                new BigDecimal("100"), new BigDecimal("101"), new BigDecimal("99"), close,
                1000L, "TEST", time);
    }
}
