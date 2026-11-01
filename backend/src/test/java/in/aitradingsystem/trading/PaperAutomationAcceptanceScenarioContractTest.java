package in.aitradingsystem.trading;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class PaperAutomationAcceptanceScenarioContractTest {
    @Test
    void scenariosCoverRequiredCustomerJourney() throws Exception {
        var text = Files.readString(Path.of("..", "docs", "product", "paper-automation-acceptance-scenarios.md"));
        assertThat(text).contains("Manual PAPER", "Automated PAPER", "Risk rejection", "Emergency stop",
            "Target/stop lifecycle", "Restart/reconnect", "LIVE");
        assertThat(text).contains("one idempotent simulated order/fill");
        assertThat(text).contains("reconstructed from persisted backend authority");
        assertThat(text).contains("No customer action in this release can submit a broker order");
    }
}
