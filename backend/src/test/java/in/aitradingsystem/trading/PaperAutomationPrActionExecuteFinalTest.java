package in.aitradingsystem.trading;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class PaperAutomationPrActionExecuteFinalTest {
    @Test
    void createPrToolCallNext() throws Exception {
        assertThat(Files.readString(Path.of("..", "docs", "product", "paper-automation-pr-action-execute-final.md")))
            .contains("CREATE PR TOOL CALL NEXT");
    }
}
