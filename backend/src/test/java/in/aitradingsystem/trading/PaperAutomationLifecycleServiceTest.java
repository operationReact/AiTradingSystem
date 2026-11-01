package in.aitradingsystem.trading;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

class PaperAutomationLifecycleServiceTest {
    @Test
    void automatedAccountCanEnterMarkAndExitPaperPosition() {
        var preferences = new AccountTradingPreferenceService();
        preferences.setExecutionMode("acct", ExecutionMode.AUTOMATED);
        var risk = new PaperRiskDecisionService(new TradingSafetyPolicy());
        var automation = new AccountPaperAutomationService(preferences,
            new AutomatedPaperDecisionService(new PaperTradeIntentService(risk)));
        var lifecycle = new PaperAutomationLifecycleService(automation, new SimulatedPaperOrderService(), new PaperPositionService());
        var opportunity = new AutomatedPaperOpportunity("opp-life", "RELIANCE", "BUY", 10,
            money("100"), money("95"), money("110"), 85, true);

        var opened = lifecycle.enter("acct", opportunity, money("10000"), money("600"), false);
        assertThat(opened.open()).isTrue();
        var marked = lifecycle.onPrice(opened, money("106"), false);
        assertThat(marked.unrealizedPnl()).isEqualByComparingTo("60");
        var closed = lifecycle.onPrice(marked, money("110"), false);
        assertThat(closed.open()).isFalse();
        assertThat(closed.realizedPnl()).isEqualByComparingTo("100");
    }

    @Test
    void emergencyStopClosesOpenPaperPositionOnNextPriceEvaluation() {
        var positions = new PaperPositionService();
        var order = new SimulatedPaperOrder("o", "k", "RELIANCE", "BUY", 10,
            money("100"), money("95"), money("110"), PaperOrderState.FILLED);
        var opened = positions.open(order);
        assertThat(positions.shouldExit(opened, money("101"), true)).isTrue();
        assertThat(positions.close(opened, money("101")).open()).isFalse();
    }

    private BigDecimal money(String value) {
        return new BigDecimal(value);
    }
}
