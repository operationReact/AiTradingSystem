package in.aitradingsystem.trading;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class PaperAutomationLastFoundationCommitTest {
    @Test
    void nextMutationMustBePrOrIntegrationDriven() throws Exception {
        var text = Files.readString(Path.of("..", "docs", "product", "paper-automation-last-foundation-commit.md"));
        assertThat(text).contains("closes the standalone foundation-writing loop");
        assertThat(text).contains("Draft PR creation or integration/CI-driven work");
    }
}
