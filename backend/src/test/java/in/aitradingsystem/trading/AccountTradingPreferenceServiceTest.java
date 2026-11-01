package in.aitradingsystem.trading;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class AccountTradingPreferenceServiceTest {
    @Test
    void accountsDefaultToPaperManualAndRemainIsolated() {
        var service = new AccountTradingPreferenceService();
        assertThat(service.get("a")).isEqualTo(new AccountTradingPreference("a", TradingMode.PAPER, ExecutionMode.MANUAL));
        service.setExecutionMode("a", ExecutionMode.AUTOMATED);
        assertThat(service.get("a").executionMode()).isEqualTo(ExecutionMode.AUTOMATED);
        assertThat(service.get("b").executionMode()).isEqualTo(ExecutionMode.MANUAL);
    }

    @Test
    void selectingLiveCannotEnableLiveTrading() {
        var service = new AccountTradingPreferenceService();
        assertThatThrownBy(() -> service.setTradingMode("a", TradingMode.LIVE))
            .isInstanceOf(IllegalStateException.class);
        assertThat(service.get("a").tradingMode()).isEqualTo(TradingMode.PAPER);
    }
}
