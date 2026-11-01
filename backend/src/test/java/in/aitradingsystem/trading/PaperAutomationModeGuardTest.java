package in.aitradingsystem.trading;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class PaperAutomationModeGuardTest {
    private final PaperAutomationModeGuard guard = new PaperAutomationModeGuard();

    @Test
    void onlyPaperAutomatedAndNotPausedPasses() {
        assertThat(guard.evaluate(new AccountTradingPreference("a", TradingMode.PAPER, ExecutionMode.AUTOMATED), false).accepted()).isTrue();
        assertThat(guard.evaluate(new AccountTradingPreference("a", TradingMode.PAPER, ExecutionMode.MANUAL), false).accepted()).isFalse();
        assertThat(guard.evaluate(new AccountTradingPreference("a", TradingMode.LIVE, ExecutionMode.AUTOMATED), false).accepted()).isFalse();
        assertThat(guard.evaluate(new AccountTradingPreference("a", TradingMode.PAPER, ExecutionMode.AUTOMATED), true).accepted()).isFalse();
    }
}
