package in.aitradingsystem.trading;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

class PaperAutomationRiskRejectionTest {
    private final PaperRiskDecisionService risk = new PaperRiskDecisionService(new TradingSafetyPolicy());

    @Test
    void returnsCustomerReadableRiskMessagesWithoutInternalCodes() {
        var decision = risk.evaluate(new RiskEvaluationRequest(TradingMode.PAPER, ExecutionMode.AUTOMATED, 100,
            money("100"), money("95"), money("110"), money("1000"), money("600"), false));
        assertThat(decision.accepted()).isFalse();
        assertThat(decision.customerMessage()).isEqualTo("This trade is larger than your available PAPER buying power.");
        assertThat(decision.customerMessage()).doesNotContain("_", "Exception", "REJECTED_");
    }

    private BigDecimal money(String value) { return new BigDecimal(value); }
}
