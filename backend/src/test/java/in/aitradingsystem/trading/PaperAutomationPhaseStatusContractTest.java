package in.aitradingsystem.trading;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class PaperAutomationPhaseStatusContractTest {
    @Test
    void phaseRemainsInProgressAtFoundationCheckpoint() throws Exception {
        var text = Files.readString(Path.of("..", "docs", "product", "paper-automation-phase-status.md"));
        assertThat(text).contains("IN PROGRESS — FOUNDATION CHECKPOINT");
        assertThat(text).contains("Production integration, actual Flutter UI, exact-head CI");
        assertThat(text).contains("Do not merge or mark the phase complete yet");
        assertThat(text).contains("LIVE/broker submission remains disabled");
    }
}
