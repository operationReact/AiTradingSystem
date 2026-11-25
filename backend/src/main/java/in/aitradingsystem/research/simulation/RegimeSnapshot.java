package in.aitradingsystem.research.simulation;

public record RegimeSnapshot(
        double volatilityMetric,
        double lowerVolatilityThreshold,
        double upperVolatilityThreshold,
        MarketRegime volatilityRegime,
        double volumeMetric,
        LiquidityRegime volumeRegime,
        String methodologyVersion) {}
