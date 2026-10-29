package in.aitradingsystem.risk;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.stereotype.Component;

/** Low-cardinality observability for the deterministic risk path. */
@Component
public final class RiskMetrics {
    private final MeterRegistry registry;
    private final Timer evaluationDuration;
    private final AtomicInteger activePolicyVersion = new AtomicInteger();
    private final AtomicInteger emergencyStopActive = new AtomicInteger();

    public RiskMetrics(MeterRegistry registry) {
        this.registry = registry;
        this.evaluationDuration = Timer.builder("risk_evaluation_duration_seconds")
                .description("Deterministic risk-policy evaluation latency")
                .register(registry);
        registry.gauge("risk_policy_version_active", activePolicyVersion);
        registry.gauge("risk_emergency_stop_active", emergencyStopActive);
    }

    public void recordEvaluation(Duration duration, RiskDecisionClass result, List<RiskRuleResult> ruleResults) {
        evaluationDuration.record(duration);
        Counter.builder("risk_evaluations_total").tag("result", result.name()).register(registry).increment();
        for (RiskRuleResult rule : ruleResults) {
            Counter.builder("risk_rule_results_total")
                    .tag("rule", rule.code().name())
                    .tag("outcome", rule.outcome().name())
                    .register(registry)
                    .increment();
        }
    }

    public void recordPositionSize(String result) {
        Counter.builder("risk_position_size_requests_total")
                .tag("result", result)
                .register(registry)
                .increment();
    }

    public void activePolicyVersion(int version) {
        activePolicyVersion.set(version);
    }

    public void emergencyStop(boolean active) {
        emergencyStopActive.set(active ? 1 : 0);
    }
}
