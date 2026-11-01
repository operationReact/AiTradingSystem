package in.aitradingsystem.trading;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

class AutomatedPaperDecisionServiceTest {
    private AutomatedPaperDecisionService service() {
        var risk = new PaperRiskDecisionService(new TradingSafetyPolicy());
        return new AutomatedPaperDecisionService(new PaperTradeIntentService(risk));
    }

    @Test
    void eligibleHighConfidenceOpportunityCanPassPaperRiskChecks() {
        var result = service().evaluate(opportunity("opp-1", 82, true), money("10000"), money("600"), false);
        assertThat(result.accepted()).isTrue();
    }

    @Test
    void ineligibleLowConfidenceAndEmergencyStoppedOpportunitiesAreRejected() {
        assertThat(service().evaluate(opportunity("opp-2", 82, false), money("10000"), money("600"), false).accepted()).isFalse();
        assertThat(service().evaluate(opportunity("opp-3", 60, true), money("10000"), money("600"), false).accepted()).isFalse();
        assertThat(service().evaluate(opportunity("opp-4", 82, true), money("10000"), money("600"), true).accepted()).isFalse();
    }

    @Test
    void opportunityProcessingIsIdempotent() {
        var service = service();
        var opportunity = opportunity("opp-same", 82, true);
        assertThat(service.evaluate(opportunity, money("10000"), money("600"), false).accepted()).isTrue();
        assertThat(service.evaluate(opportunity, money("10000"), money("600"), false).accepted()).isFalse();
    }

    private AutomatedPaperOpportunity opportunity(String id, int confidence, boolean eligible) {
        return new AutomatedPaperOpportunity(id, "RELIANCE", "BUY", 10, money("100"), money("95"), money("110"), confidence, eligible);
    }

    private BigDecimal money(String value) {
        return new BigDecimal(value);
    }
}
