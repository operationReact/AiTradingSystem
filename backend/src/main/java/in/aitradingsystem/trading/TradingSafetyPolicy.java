package in.aitradingsystem.trading;

import org.springframework.stereotype.Component;

/** Central release safety boundary for execution-mode decisions. */
@Component
public class TradingSafetyPolicy {

    /**
     * LIVE broker submission is deliberately unavailable in this release.
     * Do not make this configurable through a client request or account
     * preference. A future LIVE release must replace this with a separately
     * reviewed server-side capability gate.
     */
    public boolean brokerSubmissionAllowed(TradingMode tradingMode) {
        return false;
    }

    public boolean paperExecutionAllowed(TradingMode tradingMode) {
        return tradingMode == TradingMode.PAPER;
    }
}
