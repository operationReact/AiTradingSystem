package in.aitradingsystem.trading;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class PaperAutomationPrActionTest {
    @Test
    void createDraftPr() throws Exception {
        assertThat(Files.readString(Path.of("..", "docs", "product", "paper-automation-pr-action.md"))).contains("CREATE DRAFT PR");
    }
}
