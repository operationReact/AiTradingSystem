package in.aitradingsystem.trading;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class TradingSafetyPolicyTest {
    private final TradingSafetyPolicy policy = new TradingSafetyPolicy();

    @Test
    void paperExecutionIsAllowedOnlyForPaperMode() {
        assertThat(policy.paperExecutionAllowed(TradingMode.PAPER)).isTrue();
        assertThat(policy.paperExecutionAllowed(TradingMode.LIVE)).isFalse();
    }

    @Test
    void brokerSubmissionIsDisabledForEveryModeInThisRelease() {
        assertThat(policy.brokerSubmissionAllowed(TradingMode.PAPER)).isFalse();
        assertThat(policy.brokerSubmissionAllowed(TradingMode.LIVE)).isFalse();
    }
}
