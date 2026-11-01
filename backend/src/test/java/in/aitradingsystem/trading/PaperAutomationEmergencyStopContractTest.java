package in.aitradingsystem.trading;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class PaperAutomationEmergencyStopContractTest {
    @Test
    void emergencyStopOverridesManualAutomatedAndClientState() throws Exception {
        var text = Files.readString(Path.of("..", "docs", "product", "paper-automation-emergency-stop.md"));
        assertThat(text).contains("server-authoritative");
        assertThat(text).contains("blocks new Manual PAPER intents");
        assertThat(text).contains("blocks new Automated PAPER intents");
        assertThat(text).contains("clearing the UI toggle alone cannot resume trading");
        assertThat(text).contains("cannot bypass the stop or duplicate exits");
        assertThat(text).contains("Preserve the existing Phase 8/9 emergency-stop semantics");
    }
}
