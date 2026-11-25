package in.aitradingsystem.governance;

/**
 * Governed environments in which model intelligence may participate.
 * LIVE advisory is intentionally separate from LIVE execution influence.
 */
public enum ModelEnvironment {
    RESEARCH,
    BACKTEST,
    PAPER,
    SHADOW,
    LIVE_ADVISORY,
    LIVE_EXECUTION
}
