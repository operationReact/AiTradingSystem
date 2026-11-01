package in.aitradingsystem.trading;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class PaperAutomationStopFoundationExpansionTest {
    @Test
    void nextEngineeringValueIsIntegrationAndUi() throws Exception {
        var text = Files.readString(Path.of("..", "docs", "product", "paper-automation-stop-foundation-expansion.md"));
        assertThat(text).contains("Do not keep adding isolated in-memory models/services");
        assertThat(text).contains("adapting existing persisted services and real Flutter screens");
    }
}
