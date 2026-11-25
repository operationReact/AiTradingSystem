package in.aitradingsystem.marketdata;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

/** Shared structural validation for deterministic scanner, research and backtest candle inputs. */
public final class CandleSeriesValidator {
    private CandleSeriesValidator() {}

    public static Validation validate(List<MarketCandle> candles, Instant cutoff, Duration maxLatestAge) {
        if (candles == null || candles.isEmpty()) return Validation.invalid("EMPTY_CANDLE_SERIES");
        if (cutoff == null) return Validation.invalid("MISSING_CUTOFF");
        if (maxLatestAge != null && (maxLatestAge.isNegative() || maxLatestAge.isZero())) {
            throw new IllegalArgumentException("maxLatestAge must be positive when supplied");
        }

        Instant previous = null;
        for (MarketCandle candle : candles) {
            if (candle == null || candle.candleTime() == null) return Validation.invalid("INVALID_CANDLE");
            if (previous != null && !candle.candleTime().isAfter(previous)) {
                return Validation.invalid("DUPLICATE_OR_OUT_OF_ORDER_CANDLE");
            }
            if (candle.candleTime().isAfter(cutoff)) return Validation.invalid("FUTURE_CANDLE");
            if (!positive(candle.open()) || !positive(candle.high()) || !positive(candle.low()) || !positive(candle.close())) {
                return Validation.invalid("NON_POSITIVE_OHLC");
            }
            previous = candle.candleTime();
        }

        if (maxLatestAge != null && Duration.between(previous, cutoff).compareTo(maxLatestAge) > 0) {
            return Validation.invalid("STALE_CANDLE");
        }
        return Validation.accepted();
    }

    private static boolean positive(BigDecimal value) {
        return value != null && value.signum() > 0;
    }

    public record Validation(boolean valid, String reason) {
        public static Validation accepted() { return new Validation(true, null); }
        public static Validation invalid(String reason) { return new Validation(false, reason); }
    }
}
