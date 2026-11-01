package in.aitradingsystem.trading;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class PaperAutomationPrActionExecuteTest {
    @Test
    void executeDraftPrCreationNow() throws Exception {
        assertThat(Files.readString(Path.of("..", "docs", "product", "paper-automation-pr-action-execute.md")))
            .contains("EXECUTE DRAFT PR CREATION NOW");
    }
}
