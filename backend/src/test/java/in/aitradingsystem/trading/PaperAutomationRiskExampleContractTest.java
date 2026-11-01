package in.aitradingsystem.trading;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class PaperAutomationRiskExampleContractTest {
    @Test
    void examplesCoverAcceptedCapitalRiskRewardAndEmergencyCases() throws Exception {
        var text = Files.readString(Path.of("..", "docs", "product", "paper-automation-risk-examples.md"));
        assertThat(text).contains("Accepted example");
        assertThat(text).contains("Buying-power rejection");
        assertThat(text).contains("Risk-budget rejection");
        assertThat(text).contains("Reward/risk rejection");
        assertThat(text).contains("Emergency stop");
        assertThat(text).contains("not investment advice or production limits");
    }
}
