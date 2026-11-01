package in.aitradingsystem.trading;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

class FullAutomatedPaperEmergencyJourneyTest {
    @Test
    void emergencyStopBlocksNewAutomationAndClosesExistingPaperPosition() {
        var preferences = new AccountTradingPreferenceService();
        preferences.setExecutionMode("acct", ExecutionMode.AUTOMATED);
        var risk = new PaperRiskDecisionService(new TradingSafetyPolicy());
        var automation = new AccountPaperAutomationService(preferences,
            new AutomatedPaperDecisionService(new PaperTradeIntentService(risk)));
        var positions = new PaperPositionService();
        var lifecycle = new PaperAutomationLifecycleService(automation, new SimulatedPaperOrderService(), positions);
        var opportunity = new AutomatedPaperOpportunity("existing", "RELIANCE", "BUY", 10,
            money("100"), money("95"), money("110"), 88, true);
        var open = lifecycle.enter("acct", opportunity, money("10000"), money("600"), false);

        var closed = lifecycle.onPrice(open, money("101"), true);
        assertThat(closed.open()).isFalse();
        assertThat(closed.realizedPnl()).isEqualByComparingTo("10");

        var next = new AutomatedPaperOpportunity("new", "INFY", "BUY", 5,
            money("100"), money("95"), money("110"), 90, true);
        assertThat(automation.evaluate("acct", next, money("10000"), money("600"), true).accepted()).isFalse();
    }

    private BigDecimal money(String value) { return new BigDecimal(value); }
}
