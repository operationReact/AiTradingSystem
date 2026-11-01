package in.aitradingsystem.trading;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

class PaperAutomationCustomerSafetyTest {
    @Test
    void automatedPaperCannotStartWithoutExplicitAccountOptIn() {
        var preferences = new AccountTradingPreferenceService();
        var automation = new AccountPaperAutomationService(preferences,
            new AutomatedPaperDecisionService(new PaperTradeIntentService(
                new PaperRiskDecisionService(new TradingSafetyPolicy()))));
        var opportunity = opportunity("safe-1");
        assertThat(automation.evaluate("acct", opportunity, money("10000"), money("600"), false).accepted()).isFalse();
    }

    @Test
    void liveSelectionAndBrokerSubmissionRemainFailClosed() {
        var preferences = new AccountTradingPreferenceService();
        try { preferences.setTradingMode("acct", TradingMode.LIVE); } catch (IllegalStateException ignored) { }
        assertThat(preferences.get("acct").tradingMode()).isEqualTo(TradingMode.PAPER);
        assertThat(new TradingSafetyPolicy().brokerSubmissionAllowed(TradingMode.LIVE)).isFalse();
    }

    @Test
    void emergencyStopOverridesAutomatedOptIn() {
        var preferences = new AccountTradingPreferenceService();
        preferences.setExecutionMode("acct", ExecutionMode.AUTOMATED);
        var automation = new AccountPaperAutomationService(preferences,
            new AutomatedPaperDecisionService(new PaperTradeIntentService(
                new PaperRiskDecisionService(new TradingSafetyPolicy()))));
        assertThat(automation.evaluate("acct", opportunity("safe-2"), money("10000"), money("600"), true).accepted()).isFalse();
    }

    private AutomatedPaperOpportunity opportunity(String id) {
        return new AutomatedPaperOpportunity(id, "RELIANCE", "BUY", 10, money("100"), money("95"), money("110"), 85, true);
    }

    private BigDecimal money(String value) { return new BigDecimal(value); }
}
