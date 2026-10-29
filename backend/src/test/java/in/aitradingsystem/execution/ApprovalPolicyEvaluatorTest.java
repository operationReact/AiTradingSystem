package in.aitradingsystem.execution;

import static org.assertj.core.api.Assertions.assertThat;

import in.aitradingsystem.risk.ExecutionMode;
import in.aitradingsystem.risk.RiskDecisionClass;
import java.math.BigDecimal;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ApprovalPolicyEvaluatorTest {
    private final ApprovalPolicyEvaluator evaluator = new ApprovalPolicyEvaluator();

    @Test
    void hardRiskRejectCannotBeOverriddenByAutoPolicy() {
        var result = evaluator.evaluate(policy(ApprovalMode.AUTO), input(RiskDecisionClass.REJECTED));
        assertThat(result.decision()).isEqualTo(ApprovalEvaluationDecision.REJECT);
        assertThat(result.reasonCodes()).containsExactly("HARD_RISK_REJECTED");
    }

    @Test
    void hardRiskApprovalRequirementCannotBeAutoPromoted() {
        var result = evaluator.evaluate(policy(ApprovalMode.AUTO), input(RiskDecisionClass.APPROVAL_REQUIRED));
        assertThat(result.decision()).isEqualTo(ApprovalEvaluationDecision.PENDING);
        assertThat(result.reasonCodes()).containsExactly("HARD_RISK_APPROVAL_REQUIRED");
    }

    @Test
    void manualAlwaysRequiresApprovalAfterEligibilityPasses() {
        var result = evaluator.evaluate(policy(ApprovalMode.MANUAL), input(RiskDecisionClass.APPROVED));
        assertThat(result.decision()).isEqualTo(ApprovalEvaluationDecision.PENDING);
        assertThat(result.reasonCodes()).contains("MANUAL_APPROVAL_REQUIRED");
    }

    @Test
    void autoApprovesOnlyWhenEveryPolicyConstraintPasses() {
        var result = evaluator.evaluate(policy(ApprovalMode.AUTO), input(RiskDecisionClass.APPROVED));
        assertThat(result.decision()).isEqualTo(ApprovalEvaluationDecision.AUTO_APPROVED);
        assertThat(result.reasonCodes()).containsExactly("AUTO_POLICY_PASSED");
    }

    @Test
    void hybridFallsBackToManualForThresholdFailure() {
        var policy = policy(ApprovalMode.HYBRID);
        var input = new ApprovalPolicyInput(RiskDecisionClass.APPROVED, "TCS", new BigDecimal("10001"),
            1, new BigDecimal("1000"), BigDecimal.ZERO, LocalTime.of(10, 0));
        var result = evaluator.evaluate(policy, input);
        assertThat(result.decision()).isEqualTo(ApprovalEvaluationDecision.PENDING);
        assertThat(result.reasonCodes()).containsExactly("MAX_ORDER_NOTIONAL_EXCEEDED");
    }

    @Test
    void explicitEligibilityFailureRejectsRatherThanSilentlyPromoting() {
        var policy = new ExecutionPolicy(UUID.randomUUID(), 1, ExecutionMode.PAPER, null,
            ExecutionPolicyTradingStyle.ANY, ApprovalMode.HYBRID,
            new BigDecimal("10000"), 10, new BigDecimal("50000"), new BigDecimal("5000"),
            List.of("RELIANCE"), List.of("BLOCKED"), LocalTime.of(9, 15), LocalTime.of(15, 30),
            OffsetDateTime.now().minusDays(1), null);
        var result = evaluator.evaluate(policy,
            new ApprovalPolicyInput(RiskDecisionClass.APPROVED, "TCS", new BigDecimal("100"), 1,
                new BigDecimal("1000"), BigDecimal.ZERO, LocalTime.of(10, 0)));
        assertThat(result.decision()).isEqualTo(ApprovalEvaluationDecision.REJECT);
        assertThat(result.reasonCodes()).containsExactly("SYMBOL_NOT_ALLOWLISTED");
    }

    @Test
    void dailyLossBoundaryIsInclusiveAndRequiresManual() {
        var policy = policy(ApprovalMode.AUTO);
        var input = new ApprovalPolicyInput(RiskDecisionClass.APPROVED, "TCS", new BigDecimal("100"), 1,
            new BigDecimal("1000"), new BigDecimal("5000"), LocalTime.of(10, 0));
        var result = evaluator.evaluate(policy, input);
        assertThat(result.decision()).isEqualTo(ApprovalEvaluationDecision.PENDING);
        assertThat(result.reasonCodes()).containsExactly("MAX_DAILY_LOSS_REACHED");
    }

    private ExecutionPolicy policy(ApprovalMode mode) {
        return new ExecutionPolicy(UUID.randomUUID(), 7, ExecutionMode.PAPER, null,
            ExecutionPolicyTradingStyle.ANY, mode,
            new BigDecimal("10000"), 10, new BigDecimal("50000"), new BigDecimal("5000"),
            List.of(), List.of(), LocalTime.of(9, 15), LocalTime.of(15, 30),
            OffsetDateTime.now().minusDays(1), null);
    }

    private ApprovalPolicyInput input(RiskDecisionClass decision) {
        return new ApprovalPolicyInput(decision, "TCS", new BigDecimal("1000"), 1,
            new BigDecimal("1000"), BigDecimal.ZERO, LocalTime.of(10, 0));
    }
}
