package in.aitradingsystem.policy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import in.aitradingsystem.policy.TradingAutomationPolicy.Decision;
import in.aitradingsystem.policy.TradingAutomationPolicy.Input;
import in.aitradingsystem.policy.TradingAutomationPolicy.Mode;
import org.junit.jupiter.api.Test;

class TradingAutomationPolicyTest {
    private final TradingAutomationPolicy policy = new TradingAutomationPolicy();

    @Test
    void riskRejectionCannotBeOverriddenByAnyModeOrOperatorApproval() {
        for (Mode mode : Mode.values()) {
            var verdict = policy.evaluate(new Input(mode, false, true));
            assertThat(verdict.decision()).isEqualTo(Decision.REJECTED_BY_RISK);
            assertThat(verdict.executable()).isFalse();
        }
    }

    @Test
    void autoAuthorizesOnlyAfterRiskApproval() {
        var verdict = policy.evaluate(new Input(Mode.AUTO, true, false));
        assertThat(verdict.decision()).isEqualTo(Decision.AUTHORIZED);
        assertThat(verdict.executable()).isTrue();
    }

    @Test
    void manualRequiresOperatorApprovalAfterRiskApproval() {
        assertThat(policy.evaluate(new Input(Mode.MANUAL, true, false)).decision())
            .isEqualTo(Decision.REQUIRES_MANUAL_APPROVAL);
        assertThat(policy.evaluate(new Input(Mode.MANUAL, true, true)).decision())
            .isEqualTo(Decision.AUTHORIZED);
    }

    @Test
    void hybridRequiresOperatorApprovalAfterRiskApproval() {
        assertThat(policy.evaluate(new Input(Mode.HYBRID, true, false)).decision())
            .isEqualTo(Decision.REQUIRES_MANUAL_APPROVAL);
        assertThat(policy.evaluate(new Input(Mode.HYBRID, true, true)).decision())
            .isEqualTo(Decision.AUTHORIZED);
    }

    @Test
    void replayIsDeterministicAndVersioned() {
        Input input = new Input(Mode.HYBRID, true, false);
        assertThat(policy.evaluate(input)).isEqualTo(policy.evaluate(input));
        assertThat(policy.evaluate(input).policyVersion()).isEqualTo(TradingAutomationPolicy.VERSION);
    }

    @Test
    void nullInputAndNullModeFailClosed() {
        assertThatThrownBy(() -> policy.evaluate(null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new Input(null, true, true)).isInstanceOf(NullPointerException.class);
    }
}
