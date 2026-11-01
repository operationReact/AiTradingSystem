package in.aitradingsystem.product;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class PaperAutomationReleaseGateTest {

    @Test
    void releaseGateRequiresSharedRiskAutomationAndKeepsLiveDisabled() throws Exception {
        var path = Path.of("..", "docs", "product", "paper-automation-release-gate.md");
        assertThat(Files.exists(path)).isTrue();

        var gate = Files.readString(path);
        assertThat(gate).contains("Trading mode: `PAPER` or `LIVE`");
        assertThat(gate).contains("Execution mode: `MANUAL` or `AUTOMATED`");
        assertThat(gate).contains("Every trade path must pass the same server-authoritative risk evaluation");
        assertThat(gate).contains("emergency-stop state");
        assertThat(gate).contains("idempotent simulated orders");
        assertThat(gate).contains("LIVE broker submission remains disabled");
    }
}
