package in.aitradingsystem.scanner;

import in.aitradingsystem.marketdata.HistoricalCandleInterval;
import java.util.Set;

public record StrategyDescriptor(
        String code,
        String version,
        String displayName,
        Set<HistoricalCandleInterval> supportedIntervals,
        int minimumCandles,
        TradingStyle tradingStyle,
        boolean marketSessionRequired,
        String minimumDataQuality,
        boolean longCapable,
        boolean shortCapable) {

    public StrategyDescriptor {
        if (code == null || code.isBlank()) throw new IllegalArgumentException("code is required");
        if (version == null || version.isBlank()) throw new IllegalArgumentException("version is required");
        if (displayName == null || displayName.isBlank()) throw new IllegalArgumentException("displayName is required");
        if (supportedIntervals == null || supportedIntervals.isEmpty()) throw new IllegalArgumentException("supportedIntervals is required");
        supportedIntervals = Set.copyOf(supportedIntervals);
        if (minimumCandles <= 0) throw new IllegalArgumentException("minimumCandles must be positive");
        if (tradingStyle == null) throw new IllegalArgumentException("tradingStyle is required");
        if (minimumDataQuality == null || minimumDataQuality.isBlank()) throw new IllegalArgumentException("minimumDataQuality is required");
        if (!longCapable && !shortCapable) throw new IllegalArgumentException("strategy must support at least one direction");
    }
}
