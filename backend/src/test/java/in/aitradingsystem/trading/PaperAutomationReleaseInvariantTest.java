package in.aitradingsystem.trading;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class PaperAutomationReleaseInvariantTest {
    @Test
    void paperAutomationDoesNotImplyLiveAvailability() {
        var release = PaperAutomationReleaseState.current();
        assertThat(release.automationAvailable()).isTrue();
        assertThat(release.paperAvailable()).isTrue();
        assertThat(release.liveAvailable()).isFalse();
        assertThat(release.brokerSubmissionAvailable()).isFalse();
    }

    @Test
    void accountPreferenceCannotOverrideServerReleaseSafety() {
        var preferences = new AccountTradingPreferenceService();
        var safety = new TradingSafetyPolicy();
        preferences.setExecutionMode("acct", ExecutionMode.AUTOMATED);
        assertThat(preferences.get("acct").executionMode()).isEqualTo(ExecutionMode.AUTOMATED);
        assertThat(safety.brokerSubmissionAllowed(preferences.get("acct").tradingMode())).isFalse();
    }
}
