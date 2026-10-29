package in.aitradingsystem.risk;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

class RiskRuleTest {
    @Test
    void resultDefensivelyCopiesDetailsAndCarriesObservedLimitEvidence() {
        Map<String, Object> details = new HashMap<>();
        details.put("source", "fixture");
        RiskRuleResult result = new RiskRuleResult(
                RiskRuleCode.MAX_ORDER_NOTIONAL,
                RiskOutcome.APPROVAL_REQUIRED,
                "ORDER_NOTIONAL_REQUIRES_APPROVAL",
                new BigDecimal("75000"),
                new BigDecimal("50000"),
                "INR",
                details);

        details.put("source", "mutated");

        assertThat(result.details()).containsEntry("source", "fixture");
        assertThat(result.observedValue()).isEqualByComparingTo("75000");
        assertThat(result.limitValue()).isEqualByComparingTo("50000");
        assertThatThrownBy(() -> result.details().put("new", "value"))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void resultRequiresCodeOutcomeAndReason() {
        assertThatThrownBy(() -> new RiskRuleResult(
                null, RiskOutcome.PASS, "PASS", null, null, null, Map.of()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new RiskRuleResult(
                RiskRuleCode.EMERGENCY_STOP, null, "PASS", null, null, null, Map.of()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new RiskRuleResult(
                RiskRuleCode.EMERGENCY_STOP, RiskOutcome.PASS, " ", null, null, null, Map.of()))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
