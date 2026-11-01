package in.aitradingsystem.trading;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

class PaperAutomationSnapshotServiceTest {
    @Test
    void snapshotUsesCustomerSafeDefaultsAndLabels() {
        var preferences = new AccountTradingPreferenceService();
        var service = new PaperAutomationSnapshotService(preferences);
        var snapshot = service.snapshot("acct", false, new BigDecimal("42"), BigDecimal.ZERO);
        assertThat(snapshot.tradingMode()).isEqualTo(TradingMode.PAPER);
        assertThat(snapshot.executionMode()).isEqualTo(ExecutionMode.MANUAL);
        assertThat(snapshot.liveAvailable()).isFalse();
        assertThat(snapshot.safetyLabel()).isEqualTo("Risk protected");
    }

    @Test
    void emergencyStopUsesTradingPausedLabel() {
        var snapshot = new PaperAutomationSnapshotService(new AccountTradingPreferenceService())
            .snapshot("acct", true, BigDecimal.ZERO, BigDecimal.ZERO);
        assertThat(snapshot.tradingPaused()).isTrue();
        assertThat(snapshot.safetyLabel()).isEqualTo("Trading paused");
    }
}
