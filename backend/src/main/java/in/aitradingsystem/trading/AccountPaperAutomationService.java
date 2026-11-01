package in.aitradingsystem.trading;

import java.math.BigDecimal;
import org.springframework.stereotype.Service;

@Service
public class AccountPaperAutomationService {
    private final AccountTradingPreferenceService preferences;
    private final AutomatedPaperDecisionService automation;

    public AccountPaperAutomationService(AccountTradingPreferenceService preferences,
                                         AutomatedPaperDecisionService automation) {
        this.preferences = preferences;
        this.automation = automation;
    }

    public PaperIntentDecision evaluate(String accountId, AutomatedPaperOpportunity opportunity,
                                        BigDecimal availableCapital, BigDecimal maxRiskAmount,
                                        boolean emergencyStopActive) {
        var preference = preferences.get(accountId);
        if (preference.tradingMode() != TradingMode.PAPER) {
            return new PaperIntentDecision(false, null, "Automated trading is available only in PAPER mode right now.");
        }
        if (preference.executionMode() != ExecutionMode.AUTOMATED) {
            return new PaperIntentDecision(false, null, "Turn on Automated mode to let AI opportunities create PAPER trades.");
        }
        return automation.evaluate(opportunity, availableCapital, maxRiskAmount, emergencyStopActive);
    }
}
