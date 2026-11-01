package in.aitradingsystem.trading;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class PaperAutomationPrToolCallFinalFinalTest {
    @Test
    void endLoopAndCreateDraftPr() throws Exception {
        assertThat(Files.readString(Path.of("..", "docs", "product", "paper-automation-pr-tool-call-final-final.md")))
            .contains("END LOOP. CREATE DRAFT PR TOOL CALL");
    }
}
