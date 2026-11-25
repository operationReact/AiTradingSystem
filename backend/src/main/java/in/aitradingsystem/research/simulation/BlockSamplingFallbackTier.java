package in.aitradingsystem.research.simulation;

/** Ordered fallback used when selecting regime-compatible historical blocks. */
public enum BlockSamplingFallbackTier {
    EXACT_VOLATILITY_AND_VOLUME,
    SAME_VOLATILITY,
    ALL_ELIGIBLE
}
