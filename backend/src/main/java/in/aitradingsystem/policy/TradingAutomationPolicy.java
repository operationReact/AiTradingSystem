package in.aitradingsystem.policy;

import java.util.Objects;

/**
 * Pure deterministic authorization policy for MANUAL/AUTO/HYBRID trading modes.
 * A risk rejection is immutable: no automation mode or operator approval may override it.
 */
public final class TradingAutomationPolicy {
    public static final String VERSION = "trading-automation-policy-v1";

    public enum Mode { MANUAL, AUTO, HYBRID }
    public enum Decision { REJECTED_BY_RISK, REQUIRES_MANUAL_APPROVAL, AUTHORIZED }

    public record Input(Mode mode, boolean riskApproved, boolean operatorApproved) {
        public Input {
            Objects.requireNonNull(mode, "mode");
        }
    }

    public record Verdict(String policyVersion, Decision decision, boolean executable) {
        public Verdict {
            Objects.requireNonNull(policyVersion, "policyVersion");
            Objects.requireNonNull(decision, "decision");
            if (decision != Decision.AUTHORIZED && executable) {
                throw new IllegalArgumentException("Only AUTHORIZED verdicts may be executable");
            }
        }
    }

    public Verdict evaluate(Input input) {
        Objects.requireNonNull(input, "input");

        if (!input.riskApproved()) {
            return verdict(Decision.REJECTED_BY_RISK);
        }

        return switch (input.mode()) {
            case AUTO -> verdict(Decision.AUTHORIZED);
            case MANUAL, HYBRID -> input.operatorApproved()
                ? verdict(Decision.AUTHORIZED)
                : verdict(Decision.REQUIRES_MANUAL_APPROVAL);
        };
    }

    private static Verdict verdict(Decision decision) {
        return new Verdict(VERSION, decision, decision == Decision.AUTHORIZED);
    }
}
