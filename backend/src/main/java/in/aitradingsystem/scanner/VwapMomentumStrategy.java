package in.aitradingsystem.scanner;

import in.aitradingsystem.marketdata.HistoricalCandleInterval;
import in.aitradingsystem.marketdata.MarketCandle;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.springframework.stereotype.Component;

/** Long-only momentum candidate when price is sustainably above rolling VWAP with volume support. */
@Component
public final class VwapMomentumStrategy implements ScannerStrategy {
    public static final String NAME = "VWAP_MOMENTUM_V1";
    private static final int LOOKBACK = 20;
    private static final BigDecimal TWO = new BigDecimal("2");

    @Override public String name() { return NAME; }
    @Override public int minimumCandles() { return LOOKBACK; }

    @Override
    public StrategyDescriptor descriptor() {
        return new StrategyDescriptor(NAME, "1", "VWAP Momentum",
                Set.of(HistoricalCandleInterval.ONE_MINUTE, HistoricalCandleInterval.THREE_MINUTE,
                        HistoricalCandleInterval.FIVE_MINUTE, HistoricalCandleInterval.TEN_MINUTE,
                        HistoricalCandleInterval.FIFTEEN_MINUTE, HistoricalCandleInterval.THIRTY_MINUTE,
                        HistoricalCandleInterval.ONE_HOUR),
                minimumCandles(), TradingStyle.INTRADAY, true, "HEALTHY", true, false);
    }

    @Override
    public Optional<ScanCandidate> evaluate(ScanContext context) {
        List<MarketCandle> bars = context.candles();
        if (bars.size() < minimumCandles()) return Optional.empty();
        int start = bars.size() - LOOKBACK;
        BigDecimal pv = BigDecimal.ZERO;
        BigDecimal totalVolume = BigDecimal.ZERO;
        for (int i = start; i < bars.size(); i++) {
            MarketCandle b = bars.get(i);
            BigDecimal typical = b.high().add(b.low()).add(b.close())
                    .divide(new BigDecimal("3"), 8, RoundingMode.HALF_UP);
            BigDecimal volume = BigDecimal.valueOf(b.volume());
            pv = pv.add(typical.multiply(volume));
            totalVolume = totalVolume.add(volume);
        }
        if (totalVolume.signum() <= 0) return Optional.empty();
        BigDecimal vwap = pv.divide(totalVolume, 8, RoundingMode.HALF_UP);
        MarketCandle latest = bars.getLast();
        BigDecimal avgVolume = IndicatorMath.averageVolume(bars, start, bars.size());
        if (latest.close().compareTo(vwap) <= 0 || BigDecimal.valueOf(latest.volume()).compareTo(avgVolume) < 0) {
            return Optional.empty();
        }
        BigDecimal distance = latest.close().subtract(vwap)
                .divide(vwap.max(new BigDecimal("0.01")), 8, RoundingMode.HALF_UP);
        if (distance.compareTo(new BigDecimal("0.002")) < 0) return Optional.empty();
        BigDecimal stop = vwap.setScale(2, RoundingMode.HALF_UP);
        if (stop.signum() <= 0 || stop.compareTo(latest.close()) >= 0) return Optional.empty();
        BigDecimal target = latest.close().add(latest.close().subtract(stop).multiply(TWO)).setScale(2, RoundingMode.HALF_UP);
        double score = IndicatorMath.clamp01(0.55 + Math.min(0.30, distance.doubleValue() * 20.0));
        return Optional.of(new ScanCandidate(context.instrumentId(), context.tradingSymbol(), context.exchange(),
                context.interval(), NAME, score, latest.close(), stop, target, latest.candleTime(),
                List.of("CLOSE_ABOVE_ROLLING_VWAP", "VOLUME_CONFIRMED", "RESEARCH_CANDIDATE_ONLY")));
    }
}
