package in.aitradingsystem.trading;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

class AutomatedPaperLifecycleTest {
    @Test
    void aiOpportunityFlowsThroughRiskFillPositionAndRealizedPnlWithoutBrokerSubmission() {
        var safety = new TradingSafetyPolicy();
        var risk = new PaperRiskDecisionService(safety);
        var intents = new PaperTradeIntentService(risk);
        var automation = new AutomatedPaperDecisionService(intents);
        var fills = new SimulatedPaperOrderService();
        var positions = new PaperPositionService();

        var opportunity = new AutomatedPaperOpportunity("opp-e2e", "RELIANCE", "BUY", 10,
            money("100"), money("95"), money("110"), 85, true);
        var decision = automation.evaluate(opportunity, money("10000"), money("600"), false);
        assertThat(decision.accepted()).isTrue();
        assertThat(safety.brokerSubmissionAllowed(TradingMode.LIVE)).isFalse();

        var intent = new PaperTradeIntent("ai:opp-e2e", "RELIANCE", "BUY", 10,
            money("100"), money("95"), money("110"), TradeIntentSource.AI_AUTOMATION);
        var fill = fills.fill(intent);
        var position = positions.open(fill);
        var marked = positions.mark(position, money("106"));
        assertThat(marked.unrealizedPnl()).isEqualByComparingTo("60");

        assertThat(positions.shouldExit(marked, money("110"), false)).isTrue();
        var closed = positions.close(marked, money("110"));
        assertThat(closed.open()).isFalse();
        assertThat(closed.realizedPnl()).isEqualByComparingTo("100");
    }

    @Test
    void emergencyStopPreventsNewAutomatedIntent() {
        var automation = new AutomatedPaperDecisionService(
            new PaperTradeIntentService(new PaperRiskDecisionService(new TradingSafetyPolicy())));
        var opportunity = new AutomatedPaperOpportunity("opp-stop", "RELIANCE", "BUY", 10,
            money("100"), money("95"), money("110"), 85, true);
        assertThat(automation.evaluate(opportunity, money("10000"), money("600"), true).accepted()).isFalse();
    }

    private BigDecimal money(String value) {
        return new BigDecimal(value);
    }
}
