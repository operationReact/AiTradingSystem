package in.aitradingsystem.trading;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class LiveSubmissionReleaseGuardTest {
    @Test
    void liveBrokerSubmissionCannotBeEnabledBySelectingLiveMode() {
        var policy = new TradingSafetyPolicy();
        assertThat(policy.brokerSubmissionAllowed(TradingMode.LIVE)).isFalse();
    }
}
