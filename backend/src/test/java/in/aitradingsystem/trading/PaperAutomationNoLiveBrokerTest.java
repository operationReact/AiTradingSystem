package in.aitradingsystem.trading;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class PaperAutomationNoLiveBrokerTest {
    @Test
    void noCustomerModeCombinationCanEnableBrokerSubmissionInCurrentRelease() {
        var safety = new TradingSafetyPolicy();
        for (var tradingMode : TradingMode.values()) {
            for (var executionMode : ExecutionMode.values()) {
                assertThat(safety.brokerSubmissionAllowed(tradingMode))
                    .as("%s + %s must remain broker-disabled", tradingMode, executionMode)
                    .isFalse();
            }
        }
    }
}
