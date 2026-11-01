package in.aitradingsystem.trading;

/**
 * Customer-selected trading environment. LIVE is intentionally modeled so
 * account preferences can remain stable, but broker submission stays guarded
 * by the release safety boundary until separately enabled.
 */
public enum TradingMode {
    PAPER,
    LIVE
}
