package in.aitradingsystem.trading;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

class AutomatedPaperCapitalLifecycleTest {
    @Test
    void automatedPaperLifecycleUpdatesCapitalAndRealizedPnl() {
        var preferences = new AccountTradingPreferenceService();
        preferences.setExecutionMode("acct", ExecutionMode.AUTOMATED);
        var automation = new AccountPaperAutomationService(preferences,
            new AutomatedPaperDecisionService(new PaperTradeIntentService(
                new PaperRiskDecisionService(new TradingSafetyPolicy()))));
        var lifecycle = new PaperAutomationLifecycleService(automation, new SimulatedPaperOrderService(), new PaperPositionService());
        var capitalService = new PaperCapitalService();
        var capital = capitalService.initial(money("10000"));
        var opportunity = new AutomatedPaperOpportunity("capital", "RELIANCE", "BUY", 10,
            money("100"), money("95"), money("110"), 85, true);

        var open = lifecycle.enter("acct", opportunity, capital.availableCapital(), money("600"), false);
        capital = capitalService.onOpen(capital, open);
        assertThat(capital.availableCapital()).isEqualByComparingTo("9000");

        var closed = lifecycle.onPrice(open, money("110"), false);
        assertThat(closed.open()).isFalse();
        capital = capitalService.onClose(capital, closed);
        assertThat(capital.availableCapital()).isEqualByComparingTo("10100");
        assertThat(capital.realizedPnl()).isEqualByComparingTo("100");
    }

    private BigDecimal money(String value) { return new BigDecimal(value); }
}
