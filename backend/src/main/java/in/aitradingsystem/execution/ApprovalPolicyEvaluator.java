package in.aitradingsystem.execution;

import in.aitradingsystem.risk.RiskDecisionClass;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Service;

@Service
public class ApprovalPolicyEvaluator {

    /**
     * Hard risk always wins. Execution policy may add stricter eligibility/limits but never converts a hard-risk
     * rejection or approval requirement into automatic execution.
     */
    public ApprovalPolicyResult evaluate(ExecutionPolicy policy, ApprovalPolicyInput input) {
        if (input.riskDecisionClass() == RiskDecisionClass.REJECTED) {
            return result(policy, ApprovalEvaluationDecision.REJECT, List.of("HARD_RISK_REJECTED"));
        }

        List<String> rejectReasons = new ArrayList<>();
        List<String> manualReasons = new ArrayList<>();
        String symbol = input.symbol();

        if (policy.symbolDenylist().contains(symbol)) {
            rejectReasons.add("SYMBOL_DENYLISTED");
        }
        if (!policy.symbolAllowlist().isEmpty() && !policy.symbolAllowlist().contains(symbol)) {
            rejectReasons.add("SYMBOL_NOT_ALLOWLISTED");
        }
        if (policy.sessionStart() != null
                && (input.marketTime().isBefore(policy.sessionStart()) || !input.marketTime().isBefore(policy.sessionEnd()))) {
            rejectReasons.add("OUTSIDE_POLICY_SESSION");
        }

        if (input.orderNotional().compareTo(policy.maxOrderNotional()) > 0) {
            manualReasons.add("MAX_ORDER_NOTIONAL_EXCEEDED");
        }
        if (input.dailyTradesAfter() > policy.maxDailyTrades()) {
            manualReasons.add("MAX_DAILY_TRADES_EXCEEDED");
        }
        if (input.grossExposureAfter().compareTo(policy.maxGrossExposure()) > 0) {
            manualReasons.add("MAX_GROSS_EXPOSURE_EXCEEDED");
        }
        if (input.dailyLoss().compareTo(policy.maxDailyLoss()) >= 0) {
            manualReasons.add("MAX_DAILY_LOSS_REACHED");
        }

        if (!rejectReasons.isEmpty()) {
            return result(policy, ApprovalEvaluationDecision.REJECT, rejectReasons);
        }

        if (input.riskDecisionClass() == RiskDecisionClass.APPROVAL_REQUIRED) {
            manualReasons.add(0, "HARD_RISK_APPROVAL_REQUIRED");
            return result(policy, ApprovalEvaluationDecision.PENDING, manualReasons);
        }

        if (policy.approvalMode() == ApprovalMode.MANUAL) {
            List<String> reasons = new ArrayList<>(manualReasons);
            reasons.add(0, "MANUAL_APPROVAL_REQUIRED");
            return result(policy, ApprovalEvaluationDecision.PENDING, reasons);
        }

        if (!manualReasons.isEmpty()) {
            return result(policy, ApprovalEvaluationDecision.PENDING, manualReasons);
        }

        return result(policy, ApprovalEvaluationDecision.AUTO_APPROVED,
            List.of(policy.approvalMode() == ApprovalMode.AUTO ? "AUTO_POLICY_PASSED" : "HYBRID_AUTO_CRITERIA_PASSED"));
    }

    private static ApprovalPolicyResult result(ExecutionPolicy policy, ApprovalEvaluationDecision decision,
                                               List<String> reasons) {
        return new ApprovalPolicyResult(decision, policy.id(), policy.policyVersion(), reasons);
    }
}
