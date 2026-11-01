package in.aitradingsystem.trading;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class PaperAutomationReleaseStateTest {
    @Test
    void currentReleaseAllowsPaperAutomationButNotLiveOrBrokerSubmission() {
        var state = PaperAutomationReleaseState.current();
        assertThat(state.paperAvailable()).isTrue();
        assertThat(state.automationAvailable()).isTrue();
        assertThat(state.liveAvailable()).isFalse();
        assertThat(state.brokerSubmissionAvailable()).isFalse();
    }
}
