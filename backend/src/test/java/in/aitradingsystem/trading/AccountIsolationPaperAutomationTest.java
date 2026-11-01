package in.aitradingsystem.trading;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

class AccountIsolationPaperAutomationTest {
    @Test
    void automatedOptInForOneAccountDoesNotEnableAnotherAccount() {
        var preferences = new AccountTradingPreferenceService();
        preferences.setExecutionMode("account-a", ExecutionMode.AUTOMATED);
        var service = new AccountPaperAutomationService(preferences,
            new AutomatedPaperDecisionService(new PaperTradeIntentService(
                new PaperRiskDecisionService(new TradingSafetyPolicy()))));
        var opportunityA = opportunity("a");
        var opportunityB = opportunity("b");

        assertThat(service.evaluate("account-a", opportunityA, money("10000"), money("600"), false).accepted()).isTrue();
        assertThat(service.evaluate("account-b", opportunityB, money("10000"), money("600"), false).accepted()).isFalse();
    }

    private AutomatedPaperOpportunity opportunity(String id) {
        return new AutomatedPaperOpportunity(id, "RELIANCE", "BUY", 10, money("100"), money("95"), money("110"), 85, true);
    }

    private BigDecimal money(String value) { return new BigDecimal(value); }
}
