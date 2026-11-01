package in.aitradingsystem.trading;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

class FullAutomatedPaperJourneyTest {
    @Test
    void opportunityToRiskFillPositionPnlCapitalAndHistoryIsPaperOnly() {
        var safety = new TradingSafetyPolicy();
        var preferences = new AccountTradingPreferenceService();
        preferences.setExecutionMode("acct", ExecutionMode.AUTOMATED);
        var accountAutomation = new AccountPaperAutomationService(preferences,
            new AutomatedPaperDecisionService(new PaperTradeIntentService(new PaperRiskDecisionService(safety))));
        var lifecycle = new PaperAutomationLifecycleService(accountAutomation, new SimulatedPaperOrderService(), new PaperPositionService());
        var capitalService = new PaperCapitalService();
        var history = new PaperTradeHistoryService();
        var capital = capitalService.initial(money("10000"));
        var opportunity = new AutomatedPaperOpportunity("journey", "RELIANCE", "BUY", 10,
            money("100"), money("95"), money("110"), 88, true);

        var open = lifecycle.enter("acct", opportunity, capital.availableCapital(), money("600"), false);
        capital = capitalService.onOpen(capital, open);
        var marked = lifecycle.onPrice(open, money("106"), false);
        assertThat(marked.unrealizedPnl()).isEqualByComparingTo("60");
        var closed = lifecycle.onPrice(marked, money("110"), false);
        capital = capitalService.onClose(capital, closed);
        history.record("acct", closed);

        assertThat(history.history("acct")).hasSize(1);
        assertThat(history.history("acct").getFirst().realizedPnl()).isEqualByComparingTo("100");
        assertThat(capital.availableCapital()).isEqualByComparingTo("10100");
        assertThat(safety.brokerSubmissionAllowed(TradingMode.PAPER)).isFalse();
        assertThat(safety.brokerSubmissionAllowed(TradingMode.LIVE)).isFalse();
    }

    private BigDecimal money(String value) { return new BigDecimal(value); }
}
