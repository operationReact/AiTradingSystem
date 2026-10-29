package in.aitradingsystem.risk;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;

class RiskMetricsTest {
    @Test
    void exposesCanonicalLowCardinalityRiskMetrics() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        RiskMetrics metrics = new RiskMetrics(registry);
        RiskRuleResult pass = new RiskRuleResult(
                RiskRuleCode.EMERGENCY_STOP, RiskOutcome.PASS, "OK",
                BigDecimal.ZERO, BigDecimal.ONE, "boolean", java.util.Map.of());

        metrics.recordEvaluation(Duration.ofMillis(3), RiskDecisionClass.APPROVED, List.of(pass));
        metrics.recordPositionSize("SUCCESS");
        metrics.activePolicyVersion(7);
        metrics.emergencyStop(true);

        assertThat(registry.get("risk_evaluations_total").tag("result", "APPROVED").counter().count()).isEqualTo(1d);
        assertThat(registry.get("risk_rule_results_total").tag("rule", "EMERGENCY_STOP").tag("outcome", "PASS").counter().count()).isEqualTo(1d);
        assertThat(registry.get("risk_evaluation_duration_seconds").timer().count()).isEqualTo(1L);
        assertThat(registry.get("risk_policy_version_active").gauge().value()).isEqualTo(7d);
        assertThat(registry.get("risk_emergency_stop_active").gauge().value()).isEqualTo(1d);
        assertThat(registry.get("risk_position_size_requests_total").tag("result", "SUCCESS").counter().count()).isEqualTo(1d);
    }
}
