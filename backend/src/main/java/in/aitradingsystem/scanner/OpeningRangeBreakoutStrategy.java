package in.aitradingsystem.scanner;

import in.aitradingsystem.marketdata.MarketCandle;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** India cash-market opening-range breakout using the 09:15-09:30 IST range. */
public final class OpeningRangeBreakoutStrategy implements ScannerStrategy {
    public static final String NAME = "OPENING_RANGE_BREAKOUT_V1";
    private static final ZoneId INDIA = ZoneId.of("Asia/Kolkata");
    private static final LocalTime OPEN = LocalTime.of(9, 15);
    private static final LocalTime RANGE_END = LocalTime.of(9, 30);
    private static final BigDecimal TWO = new BigDecimal("2");

    @Override public String name() { return NAME; }
    @Override public int minimumCandles() { return 4; }

    @Override
    public Optional<ScanCandidate> evaluate(ScanContext context) {
        List<MarketCandle> bars = context.candles();
        MarketCandle latest = bars.getLast();
        LocalDate session = latest.candleTime().atZone(INDIA).toLocalDate();
        List<MarketCandle> opening = new ArrayList<>();
        for (MarketCandle bar : bars) {
            var local = bar.candleTime().atZone(INDIA);
            LocalTime time = local.toLocalTime();
            if (local.toLocalDate().equals(session) && !time.isBefore(OPEN) && time.isBefore(RANGE_END)) opening.add(bar);
        }
        if (opening.size() < 3 || latest.candleTime().atZone(INDIA).toLocalTime().isBefore(RANGE_END)) return Optional.empty();
        BigDecimal rangeHigh = opening.stream().map(MarketCandle::high).max(BigDecimal::compareTo).orElseThrow();
        BigDecimal avgVolume = opening.stream().map(b -> BigDecimal.valueOf(b.volume())).reduce(BigDecimal.ZERO, BigDecimal::add)
                .divide(BigDecimal.valueOf(opening.size()), 8, RoundingMode.HALF_UP);
        if (latest.close().compareTo(rangeHigh) <= 0 || BigDecimal.valueOf(latest.volume()).compareTo(avgVolume) < 0) return Optional.empty();
        BigDecimal stop = rangeHigh.setScale(2, RoundingMode.HALF_UP);
        if (stop.signum() <= 0 || stop.compareTo(latest.close()) >= 0) return Optional.empty();
        BigDecimal target = latest.close().add(latest.close().subtract(stop).multiply(TWO)).setScale(2, RoundingMode.HALF_UP);
        double breakout = latest.close().subtract(rangeHigh)
                .divide(rangeHigh.max(new BigDecimal("0.01")), 8, RoundingMode.HALF_UP).doubleValue();
        double score = IndicatorMath.clamp01(0.58 + Math.min(0.30, breakout * 12.0));
        return Optional.of(new ScanCandidate(context.instrumentId(), context.tradingSymbol(), context.exchange(),
                context.interval(), NAME, score, latest.close(), stop, target, latest.candleTime(),
                List.of("CLOSE_ABOVE_OPENING_RANGE", "OPENING_VOLUME_CONFIRMED", "RESEARCH_CANDIDATE_ONLY")));
    }
}
