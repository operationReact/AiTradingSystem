package in.aitradingsystem.trading;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class PaperAutomationExitCriteriaContractTest {
    @Test
    void phaseExitRequiresPersistedE2eAuthUiVisualDeviceAndLiveSafety() throws Exception {
        var text = Files.readString(Path.of("..", "docs", "product", "paper-automation-exit-criteria.md"));
        assertThat(text).contains("persisted account-scoped mode state integrated");
        assertThat(text).contains("persisted automated order/fill/position/capital/P&L/history journey");
        assertThat(text).contains("Secure Sign In/account session");
        assertThat(text).contains("actual Flutter customer controls/lifecycle implemented");
        assertThat(text).contains("backend-connected Android physical-device acceptance");
        assertThat(text).contains("LIVE/broker submission disabled");
    }
}
