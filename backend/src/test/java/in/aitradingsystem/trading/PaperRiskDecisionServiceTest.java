package in.aitradingsystem.trading;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

class PaperRiskDecisionServiceTest {
    private final PaperRiskDecisionService service = new PaperRiskDecisionService(new TradingSafetyPolicy());

    @Test
    void acceptsManualPaperTradeWithinCapitalAndRiskBudget() {
        var decision = service.evaluate(request(TradingMode.PAPER, ExecutionMode.MANUAL, false, "100", "95", "110", "10000", "600", 10));
        assertThat(decision.accepted()).isTrue();
    }

    @Test
    void acceptsAutomatedPaperTradeThroughSameRiskBoundary() {
        var decision = service.evaluate(request(TradingMode.PAPER, ExecutionMode.AUTOMATED, false, "100", "95", "110", "10000", "600", 10));
        assertThat(decision.accepted()).isTrue();
    }

    @Test
    void rejectsLiveModeAndEmergencyStop() {
        assertThat(service.evaluate(request(TradingMode.LIVE, ExecutionMode.MANUAL, false, "100", "95", "110", "10000", "600", 10)).accepted()).isFalse();
        assertThat(service.evaluate(request(TradingMode.PAPER, ExecutionMode.AUTOMATED, true, "100", "95", "110", "10000", "600", 10)).accepted()).isFalse();
    }

    @Test
    void rejectsCapitalRiskAndPoorRewardToRisk() {
        assertThat(service.evaluate(request(TradingMode.PAPER, ExecutionMode.MANUAL, false, "100", "95", "110", "500", "600", 10)).accepted()).isFalse();
        assertThat(service.evaluate(request(TradingMode.PAPER, ExecutionMode.MANUAL, false, "100", "80", "130", "10000", "100", 10)).accepted()).isFalse();
        assertThat(service.evaluate(request(TradingMode.PAPER, ExecutionMode.MANUAL, false, "100", "90", "105", "10000", "1000", 10)).accepted()).isFalse();
    }

    private RiskEvaluationRequest request(TradingMode tradingMode, ExecutionMode executionMode, boolean emergency,
                                          String entry, String stop, String target, String capital, String risk, int quantity) {
        return new RiskEvaluationRequest(tradingMode, executionMode, quantity,
            new BigDecimal(entry), new BigDecimal(stop), new BigDecimal(target),
            new BigDecimal(capital), new BigDecimal(risk), emergency);
    }
}
