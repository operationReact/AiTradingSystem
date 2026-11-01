package in.aitradingsystem.trading;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

class AccountPaperAutomationServiceTest {
    @Test
    void automationRequiresAccountToOptIntoAutomatedMode() {
        var preferences = new AccountTradingPreferenceService();
        var automated = new AutomatedPaperDecisionService(
            new PaperTradeIntentService(new PaperRiskDecisionService(new TradingSafetyPolicy())));
        var service = new AccountPaperAutomationService(preferences, automated);
        var opportunity = new AutomatedPaperOpportunity("opp-1", "RELIANCE", "BUY", 10,
            money("100"), money("95"), money("110"), 85, true);

        assertThat(service.evaluate("acct", opportunity, money("10000"), money("600"), false).accepted()).isFalse();
        preferences.setExecutionMode("acct", ExecutionMode.AUTOMATED);
        assertThat(service.evaluate("acct", opportunity, money("10000"), money("600"), false).accepted()).isTrue();
    }

    private BigDecimal money(String value) {
        return new BigDecimal(value);
    }
}
