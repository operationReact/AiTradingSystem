package in.aitradingsystem.trading;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class PaperAutomationRiskRulesContractTest {
    @Test
    void integrationMustNotWeakenExistingRiskPolicy() throws Exception {
        var text = Files.readString(Path.of("..", "docs", "product", "paper-automation-risk-rules.md"));
        assertThat(text).contains("PAPER mode only; LIVE is rejected");
        assertThat(text).contains("Emergency stop rejects new intents");
        assertThat(text).contains("Trade notional cannot exceed available PAPER buying power");
        assertThat(text).contains("cannot exceed configured max risk amount");
        assertThat(text).contains("preserve stricter existing limits");
        assertThat(text).contains("rather than weakening them");
    }
}
