package in.aitradingsystem.trading;

import org.springframework.stereotype.Component;

@Component
public class PaperAutomationModeGuard {
    public RiskDecision evaluate(AccountTradingPreference preference, boolean emergencyStopActive) {
        if (preference == null) return RiskDecision.reject("Your trading preferences are unavailable. Please try again.");
        if (preference.tradingMode() != TradingMode.PAPER) {
            return RiskDecision.reject("Automated trading is available only in PAPER mode right now.");
        }
        if (preference.executionMode() != ExecutionMode.AUTOMATED) {
            return RiskDecision.reject("Turn on Automated mode to let AI opportunities create PAPER trades.");
        }
        if (emergencyStopActive) {
            return RiskDecision.reject("Trading is paused by your safety controls.");
        }
        return RiskDecision.accept();
    }
}
